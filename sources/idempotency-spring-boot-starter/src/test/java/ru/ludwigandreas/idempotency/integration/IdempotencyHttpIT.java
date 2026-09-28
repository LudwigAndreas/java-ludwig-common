package ru.ludwigandreas.idempotency.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.ludwigandreas.idempotency.api.IdempotencyHeaders;

/**
 * The HTTP surface, end to end: a real request, a real handler, a real claim table.
 *
 * <p>What is under test here is not the store - {@code IdempotencyStoreIT} covers that - but the four
 * answers the filter gives and, more importantly, whether the handler ran. A replayed body proves the filter
 * returned something; the execution counter proves nothing ran twice.
 */
@SpringBootTest(classes = {TestApplication.class, TestOrderController.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ludwig.idempotency.http.enabled=true",
            "ludwig.idempotency.purge.enabled=false",
            // Short enough that the in-flight case does not depend on the default minute, long enough that
            // a container starting slowly cannot expire a claim mid-case.
            "ludwig.idempotency.lease=30s"
        })
class IdempotencyHttpIT extends PostgresBackedTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TestOrderController controller;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshLatches() {
        controller.reset();
    }

    /**
     * The case the feature exists for: a caller whose {@code POST} timed out retries it.
     *
     * <p>A duplicate flag would tell them "you already did this", which does not tell them what was created,
     * where it is, or what its id was - all of which were in the response the timeout ate. This asserts they
     * get the original {@code 201}, its body and its {@code Location}, and that nothing ran again.
     */
    @Test
    @DisplayName("a retried POST replays the original 201, its body and its Location, and runs nothing")
    void retryReplaysTheOriginalResponse() {
        String key = UUID.randomUUID().toString();
        int before = controller.executions();

        ResponseEntity<String> first = post("/api/v1/orders", key, "{\"amount\":10}");
        ResponseEntity<String> second = post("/api/v1/orders", key, "{\"amount\":10}");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(second.getHeaders().getFirst("Location"))
                .isEqualTo(first.getHeaders().getFirst("Location"));
        assertThat(second.getHeaders().getFirst(IdempotencyHeaders.REPLAYED)).isEqualTo("true");
        assertThat(first.getHeaders().getFirst(IdempotencyHeaders.REPLAYED))
                .describedAs("the original must not claim to be a replay")
                .isNull();
        assertThat(controller.executions())
                .describedAs("the handler must not run for a duplicate")
                .isEqualTo(before + 1);
    }

    /**
     * A re-serialised retry is still a retry.
     *
     * <p>If this failed, the feature would be worse than absent: a client that re-encoded its body on retry
     * would get a 422 and have no way to recover, because generating a new key is exactly what it must not
     * do.
     */
    @Test
    @DisplayName("a retry whose JSON keys are reordered is still a retry")
    void reorderedJsonIsStillARetry() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = post("/api/v1/orders", key, "{\"amount\":10,\"note\":\"x\"}");
        ResponseEntity<String> second = post("/api/v1/orders", key, "{\"note\":\"x\",\"amount\":10}");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(second.getHeaders().getFirst(IdempotencyHeaders.REPLAYED)).isEqualTo("true");
    }

    /**
     * Case 5: the same key with a different body is refused, and the first response is <em>not</em> returned.
     *
     * <p>Returning it would hand a client that recycles keys somebody else's resource with a 200 - a
     * data-integrity failure that presents as "the API returned the wrong data" and leaves no trace anywhere.
     */
    @Test
    @DisplayName("the same key with a different body is 422, and the first response is not returned")
    void reusedKeyIsRefused() {
        String key = UUID.randomUUID().toString();
        ResponseEntity<String> first = post("/api/v1/orders", key, "{\"amount\":10}");

        ResponseEntity<String> second = post("/api/v1/orders", key, "{\"amount\":9999}");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(second.getBody()).doesNotContain("\"sequence\"");
        assertThat(second.getBody())
                .describedAs("the problem must name the key the client reused")
                .contains(key);
        assertThat(second.getBody()).contains("ludwig.idempotency.error.fingerprint-mismatch");
        // Charset asserted explicitly, not incidentally: these messages are localized, and a servlet's
        // default response encoding is ISO-8859-1, which delivers the Russian bundle as mojibake.
        assertThat(second.getHeaders().getContentType()).isNotNull();
        assertThat(second.getHeaders().getContentType().isCompatibleWith(
                MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(second.getHeaders().getContentType().getCharset())
                .isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(first.getBody()).contains("\"sequence\"");
    }

    /**
     * Case 3: an in-flight duplicate gets a 409 and a {@code Retry-After}, not a response that does not
     * exist yet.
     */
    @Test
    @DisplayName("a duplicate arriving while the first is still running is 409 with Retry-After")
    void inFlightDuplicateIsRefused() throws Exception {
        String key = UUID.randomUUID().toString();
        CompletableFuture<ResponseEntity<String>> slow = CompletableFuture.supplyAsync(() ->
                post("/api/v1/orders/slow", key, "{\"amount\":1}"));

        assertThat(controller.awaitStarted()).isTrue();
        ResponseEntity<String> duplicate = post("/api/v1/orders/slow", key, "{\"amount\":1}");

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getHeaders().getFirst("Retry-After"))
                .describedAs("a client needs to be told when to come back, not just that it collided")
                .isNotNull();
        assertThat(duplicate.getBody()).contains("ludwig.idempotency.error.in-progress");

        controller.release();
        assertThat(slow.get(20, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /**
     * A handler that threw must leave the key claimable.
     *
     * <p>Otherwise one transient failure becomes a window - a whole lease long - in which the operation
     * cannot be performed at all, which is a worse outcome than the double execution the claim prevents.
     */
    @Test
    @DisplayName("a failed request frees its key, so the retry is allowed through")
    void failedRequestFreesTheKey() {
        String key = UUID.randomUUID().toString();
        int before = controller.executions();

        ResponseEntity<String> first = post("/api/v1/orders/failing", key, "{\"amount\":1}");
        ResponseEntity<String> retry = post("/api/v1/orders/failing", key, "{\"amount\":1}");

        assertThat(first.getStatusCode().is5xxServerError()).isTrue();
        assertThat(retry.getStatusCode().is5xxServerError()).isTrue();
        assertThat(controller.executions())
                .describedAs("the retry of a failed request must reach the handler")
                .isEqualTo(before + 2);
        assertThat(claimState(key)).isEqualTo("FAILED");
    }

    /**
     * A 5xx is not stored for replay.
     *
     * <p>Storing one would make a transient failure permanent for that key: every later retry would be
     * answered with the failure rather than being allowed to succeed.
     */
    @Test
    @DisplayName("a 5xx response is not stored for replay")
    void serverErrorsAreNotStored() {
        String key = UUID.randomUUID().toString();
        post("/api/v1/orders/failing", key, "{\"amount\":1}");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_claim WHERE idempotency_key = ? AND response_status"
                        + " IS NOT NULL", Long.class, key)).isZero();
    }

    /** A request with no key is let through untouched, and leaves no claim behind. */
    @Test
    @DisplayName("a request with no key is not claimed")
    void noKeyMeansNoClaim() {
        int before = controller.executions();
        long claimsBefore = totalClaims();

        ResponseEntity<String> response = post("/api/v1/orders", null, "{\"amount\":3}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controller.executions()).isEqualTo(before + 1);
        assertThat(totalClaims()).isEqualTo(claimsBefore);
    }

    @Test
    @DisplayName("a scope is one per endpoint, so one key against two endpoints is two claims")
    void scopeIsPerEndpoint() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> orders = post("/api/v1/orders", key, "{\"amount\":1}");
        ResponseEntity<String> draft = post("/api/v1/drafts", key, "{\"amount\":1}");

        assertThat(orders.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // A client that generates one key per user action and calls two endpoints with it is not sending a
        // duplicate, and one scope for the whole service would answer the second call with the first's body.
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(draft.getHeaders().getFirst(IdempotencyHeaders.REPLAYED)).isNull();
        assertThat(draft.getBody()).contains("draft");
        assertThat(jdbc.queryForObject(
                "SELECT count(DISTINCT scope) FROM idempotency_claim WHERE idempotency_key = ?",
                Long.class, key)).isEqualTo(2);
    }

    /** Every response carries the key back, so a client need not keep its own map across a retry. */
    @Test
    @DisplayName("the key is echoed on the original and on the replay")
    void keyIsEchoed() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = post("/api/v1/orders", key, "{\"amount\":2}");
        ResponseEntity<String> second = post("/api/v1/orders", key, "{\"amount\":2}");

        assertThat(first.getHeaders().getFirst(IdempotencyHeaders.ECHO)).isEqualTo(key);
        assertThat(second.getHeaders().getFirst(IdempotencyHeaders.ECHO)).isEqualTo(key);
    }

    private ResponseEntity<String> post(String path, String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.set(IdempotencyHeaders.KEY, key);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String claimState(String key) {
        List<String> states = jdbc.queryForList(
                "SELECT state FROM idempotency_claim WHERE idempotency_key = ?", String.class, key);
        return states.isEmpty() ? null : states.get(0);
    }

    private long totalClaims() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM idempotency_claim", Long.class);
        return count == null ? 0L : count;
    }
}
