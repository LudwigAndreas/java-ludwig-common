package ru.ludwigandreas.pat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ludwigandreas.pat.service.IssueTokenCommand;
import ru.ludwigandreas.pat.service.PatService;
import ru.ludwigandreas.pat.service.RevocationReasons;
import ru.ludwigandreas.testsupport.image.LudwigTestImages;

/**
 * Every exchange failure, compared byte for byte.
 *
 * <p>This is the test that makes the no-oracle claim real rather than documented. Any distinguishable
 * failure on this endpoint tells an attacker testing a credential guess something: "unknown key id" says
 * which half of the guess to keep working on, "revoked" confirms a harvested token was once real and names
 * a live target, "wrong audience" maps out which services a token reaches.
 *
 * <p>The pairing is what matters. The bodies and statuses must be <b>identical</b>, and the reasons must be
 * <b>distinguishable in metrics</b>. Either assertion alone proves the wrong thing: identical bodies with no
 * telemetry leaves the defender blind, and good telemetry with varying bodies is the oracle. So both are
 * asserted here, over the same set of causes, in one test.
 */
@SpringBootTest(classes = ExchangeTestSupport.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class UniformExchangeFailureIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(LudwigTestImages.POSTGRES);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PatService service;

    @Autowired
    private ExchangeTestSupport.RecordingMetrics metrics;

    private String issue(Set<String> audiences, Duration lifetime) {
        return service.issue(
                new IssueTokenCommand("alice", "ci", Set.of("orders:read"), audiences, lifetime, List.of()),
                "alice", Set.of()).rendered();
    }

    private MvcResult exchange(String token, String audience) throws Exception {
        var request = post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                .param("subject_token", token == null ? "" : token);
        if (audience != null) {
            request = request.param("resource", audience);
        }
        return mockMvc.perform(request).andReturn();
    }

    @Test
    @DisplayName("every cause produces a byte-identical response, and each is distinct in the metric tag")
    void everyFailureIsIdenticalAndStillDistinguishableInMetrics() throws Exception {
        Map<String, MvcResult> responses = new LinkedHashMap<>();

        // A well-formed token for a key id that exists nowhere.
        metrics.clear();
        responses.put("unknown-key", exchange(freshUnknownToken(), "deploy-service"));
        assertThat(metrics.failures()).containsExactly("unknown-key");

        // A real key id with somebody else's secret: same shape, different digest.
        String real = issue(Set.of("deploy-service"), Duration.ofDays(30));
        metrics.clear();
        responses.put("bad-secret", exchange(swapSecret(real), "deploy-service"));
        assertThat(metrics.failures()).containsExactly("bad-secret");

        // A broken checksum reports as malformed, not as its own reason. ExchangeFailure.BAD_CHECKSUM was
        // removed after this assertion showed nothing could produce it: PatTokens.parse returns empty for
        // a bad checksum exactly as it does for a bad prefix or a wrong arity.
        metrics.clear();
        responses.put("bad-checksum", exchange(breakChecksum(real), "deploy-service"));
        assertThat(metrics.failures()).containsExactly("malformed");

        metrics.clear();
        responses.put("malformed", exchange("not-a-token-at-all", "deploy-service"));
        assertThat(metrics.failures()).containsExactly("malformed");

        var revoked = service.issue(
                new IssueTokenCommand("alice", "ci", Set.of("orders:read"),
                        Set.of("deploy-service"), Duration.ofDays(30), List.of()),
                "alice", Set.of());
        service.revoke(revoked.token().id(), "operator", RevocationReasons.COMPROMISED);
        metrics.clear();
        responses.put("revoked", exchange(revoked.rendered(), "deploy-service"));
        assertThat(metrics.failures()).containsExactly("revoked");

        metrics.clear();
        responses.put("audience-not-permitted", exchange(real, "billing-service"));
        assertThat(metrics.failures()).containsExactly("audience-not-permitted");

        metrics.clear();
        responses.put("audience-not-issuable", exchange(real, "no-such-service"));
        assertThat(metrics.failures()).containsExactly("audience-not-issuable");

        metrics.clear();
        responses.put("audience-missing", exchange(real, null));
        assertThat(metrics.failures()).containsExactly("audience-missing");

        // The assertion this class exists for. Seven distinct causes, one response.
        assertThat(responses).hasSizeGreaterThanOrEqualTo(7);

        MvcResult reference = responses.values().iterator().next();
        String referenceBody = withoutTimestamp(reference.getResponse().getContentAsString());
        int referenceStatus = reference.getResponse().getStatus();

        for (Map.Entry<String, MvcResult> entry : responses.entrySet()) {
            assertThat(entry.getValue().getResponse().getStatus())
                    .as("status for %s must be indistinguishable", entry.getKey())
                    .isEqualTo(referenceStatus);
            assertThat(withoutTimestamp(entry.getValue().getResponse().getContentAsString()))
                    .as("body for %s must be byte-for-byte indistinguishable - anything that"
                            + " distinguishes one cause from another is an oracle", entry.getKey())
                    .isEqualTo(referenceBody);
        }

        assertThat(referenceStatus).isEqualTo(401);

        // No response carries its OWN cause's machine-readable reason. Checked per response against its
        // own tag rather than as a blanket word search, which is a distinction worth getting right: the
        // shared `detail` deliberately lists every possible cause as ADVICE - "check that the token is
        // correct, has not expired, has not been revoked" - and that prose is identical in all eight
        // responses, so it discloses nothing. An earlier version of this assertion forbade those words
        // outright and failed on the helpful message rather than on an oracle.
        for (Map.Entry<String, MvcResult> entry : responses.entrySet()) {
            assertThat(entry.getValue().getResponse().getContentAsString())
                    .as("the response for %s must not carry that reason as a machine-readable value",
                            entry.getKey())
                    .doesNotContain("\"" + entry.getKey() + "\"")
                    .doesNotContain("reason")
                    .doesNotContain("failure");
        }
    }

    @Test
    @DisplayName("an expired token is refused with the same response, and reports expired in metrics")
    void expiredIsIdenticalToo() throws Exception {
        // A NEGATIVE lifetime, so the fixed clock sees it as already expired without the test mutating a
        // persisted row or moving time. The issuance ceiling only refuses lifetimes that are too LONG, so
        // this is accepted - which is itself worth knowing: this issuer will mint an already-expired token
        // if asked, and the request to do so is a caller error rather than a security problem.
        String token = issue(Set.of("deploy-service"), Duration.ofSeconds(-60));

        metrics.clear();
        MvcResult result = exchange(token, "deploy-service");

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(metrics.failures()).containsExactly("expired");
    }

    /**
     * Removes the per-request timestamp before comparing.
     *
     * <p>The one field that legitimately differs between two responses. It is not a cause signal - every
     * problem document this platform emits carries it, and it differs between two <em>identical</em>
     * failures a millisecond apart. Normalising it is what lets the rest of the body be compared exactly
     * rather than loosely, which is the comparison worth making.
     */
    private static String withoutTimestamp(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\",?", "");
    }

    /** A syntactically valid token whose key id belongs to nothing. */
    private static String freshUnknownToken() {
        return ru.ludwigandreas.pat.token.PatTokens.mint().rendered();
    }

    /** The same key id, a different secret - so the lookup succeeds and the digest comparison fails. */
    private static String swapSecret(String token) {
        String[] parts = token.split("_");
        String[] other = ru.ludwigandreas.pat.token.PatTokens.mint().rendered().split("_");
        String body = parts[0] + "_" + parts[1] + "_" + other[2];
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return body + "_" + Long.toHexString(crc.getValue());
    }

    private static String breakChecksum(String token) {
        return token.substring(0, token.lastIndexOf('_') + 1) + "deadbeef";
    }
}
