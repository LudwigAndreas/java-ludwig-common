package ru.ludwigandreas.restclient.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.restclient.config.ClientProperties;
import ru.ludwigandreas.restclient.config.ClientPropertiesMerger;
import ru.ludwigandreas.restclient.resilience.AttemptOutcome;
import ru.ludwigandreas.restclient.resilience.RetryPolicy;

/**
 * The retry predicate: the decision that, when wrong in either direction, either doubles a payment
 * or gives up on a blip.
 */
class RetryPolicyTest {

    private static final Duration ANY = Duration.ofMillis(10);

    @Test
    @DisplayName("a retryable status on an idempotent method is retried")
    void retriesIdempotentMethodOnRetryableStatus() {
        assertThat(policy().shouldRetry(status(503, 1), "GET", null, 0)).isTrue();
    }

    @Test
    @DisplayName("a POST is not retried by default, whatever the status")
    void doesNotRetryPostByDefault() {
        assertThat(policy().shouldRetry(status(503, 1), "POST", null, 0)).isFalse();
    }

    @Test
    @DisplayName("a POST is retried when the caller opts in with X-Ludwig-Retry")
    void retriesPostOnExplicitOptIn() {
        assertThat(policy().shouldRetry(status(503, 1), "POST", Boolean.TRUE, 0)).isTrue();
    }

    @Test
    @DisplayName("a GET is not retried when the caller opts out")
    void honoursOptOutOnIdempotentMethod() {
        assertThat(policy().shouldRetry(status(503, 1), "GET", Boolean.FALSE, 0)).isFalse();
    }

    @Test
    @DisplayName("a 4xx is not retried - the peer answered, and the answer will not change")
    void doesNotRetryClientErrors() {
        assertThat(policy().shouldRetry(status(404, 1), "GET", null, 0)).isFalse();
        assertThat(policy().shouldRetry(status(422, 1), "GET", null, 0)).isFalse();
    }

    @Test
    @DisplayName("a connection that never opened is retried even on a POST")
    void retriesConnectionFailuresRegardlessOfMethod() {
        AttemptOutcome outcome = new AttemptOutcome(0, new ConnectException("refused"), -1, ANY, 1);
        assertThat(policy().shouldRetry(outcome, "POST", null, 0)).isTrue();
    }

    @Test
    @DisplayName("a read timeout on a POST is NOT retried - the peer may have processed it")
    void doesNotRetryTimedOutPost() {
        AttemptOutcome outcome = new AttemptOutcome(0, new SocketTimeoutException("read"), -1, ANY, 1);
        assertThat(policy().shouldRetry(outcome, "POST", null, 0)).isFalse();
        assertThat(policy().shouldRetry(outcome, "GET", null, 0)).isTrue();
    }

    @Test
    @DisplayName("the attempt limit stops the loop")
    void stopsAtMaxAttempts() {
        assertThat(policy().shouldRetry(status(503, 3), "GET", null, 0)).isFalse();
    }

    @Test
    @DisplayName("a spent elapsed-time budget stops the loop before the next wait")
    void stopsWhenBudgetIsSpent() {
        RetryPolicy policy = policy();
        assertThat(policy.shouldRetry(status(503, 1), "GET", null, 60_000)).isFalse();
    }

    @Test
    @DisplayName("Retry-After wins over the computed backoff, capped by max-retry-after")
    void honoursRetryAfterWithinItsCap() {
        RetryPolicy policy = policy();
        assertThat(policy.waitMillis(new AttemptOutcome(429, null, 5_000, ANY, 1))).isEqualTo(5_000);
        assertThat(policy.waitMillis(new AttemptOutcome(429, null, 600_000, ANY, 1)))
                .isEqualTo(Duration.ofSeconds(30).toMillis());
    }

    @Test
    @DisplayName("with no Retry-After the wait grows and stays inside the jitter band")
    void fallsBackToJitteredExponentialBackoff() {
        RetryPolicy policy = policy();
        long first = policy.waitMillis(status(503, 1));
        long third = policy.waitMillis(status(503, 3));

        // base 200ms, multiplier 2, jitter 0.5 -> attempt 1 in [100, 300], attempt 3 in [400, 1200]
        assertThat(first).isBetween(100L, 300L);
        assertThat(third).isBetween(400L, 1200L);
    }

    @Test
    @DisplayName("an exception type listed in retry-on-exception is retried")
    void retriesListedExceptionTypes() {
        ClientProperties props = merged();
        RetryPolicy policy = new RetryPolicy(props.getResilience().getRetry(),
                List.of(IllegalStateException.class), Clock.systemUTC());
        AttemptOutcome outcome = new AttemptOutcome(0, new IllegalStateException("boom"), -1, ANY, 1);

        assertThat(policy.shouldRetry(outcome, "GET", null, 0)).isTrue();
    }

    @Test
    @DisplayName("an unlisted application exception is not retried")
    void doesNotRetryUnlistedExceptions() {
        AttemptOutcome outcome = new AttemptOutcome(0, new IllegalArgumentException("bad"), -1, ANY, 1);
        assertThat(policy().shouldRetry(outcome, "GET", null, 0)).isFalse();
    }

    @Test
    @DisplayName("a disabled retry allows exactly one attempt")
    void disabledRetryMeansOneAttempt() {
        ClientProperties props = merged();
        props.getResilience().getRetry().setEnabled(false);
        RetryPolicy policy = new RetryPolicy(props.getResilience().getRetry(), List.of(),
                Clock.systemUTC());

        assertThat(policy.maxAttempts()).isEqualTo(1);
        assertThat(policy.shouldRetry(status(503, 1), "GET", null, 0)).isFalse();
    }

    @Test
    @DisplayName("the retry reason is a bounded, taggable label")
    void reasonIsBounded() {
        assertThat(policy().reason(status(503, 1))).isEqualTo("status:503");
        assertThat(policy().reason(new AttemptOutcome(0, new IOException("x"), -1, ANY, 1)))
                .isEqualTo("IOException");
    }

    private RetryPolicy policy() {
        return new RetryPolicy(merged().getResilience().getRetry(), List.of(), Clock.systemUTC());
    }

    private ClientProperties merged() {
        ClientProperties client = new ClientProperties();
        client.setBaseUrl("https://example.internal");
        return ClientPropertiesMerger.resolve(new ClientProperties(), client);
    }

    private AttemptOutcome status(int code, int attempt) {
        return new AttemptOutcome(code, null, -1, ANY, attempt);
    }
}
