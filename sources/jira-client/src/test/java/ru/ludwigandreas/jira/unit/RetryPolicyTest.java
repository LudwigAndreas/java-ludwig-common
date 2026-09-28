package ru.ludwigandreas.jira.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.jira.http.HttpMethod;
import ru.ludwigandreas.jira.http.JiraRequest;
import ru.ludwigandreas.jira.http.RetryPolicy;

class RetryPolicyTest {

    private static final URI ANY = URI.create("https://jira.example.com/rest/api/2/myself");

    private static JiraRequest request(HttpMethod method) {
        return JiraRequest.builder(method, ANY).build();
    }

    @Test
    void treatsPostAsTheOnlyNonIdempotentMethod() {
        assertThat(HttpMethod.POST.isIdempotent()).isFalse();
        assertThat(HttpMethod.PUT.isIdempotent()).isTrue();
        assertThat(HttpMethod.DELETE.isIdempotent()).isTrue();
        assertThat(HttpMethod.GET.isIdempotent()).isTrue();
    }

    @Test
    void retriesOnlyTheTransientStatuses() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertThat(policy.shouldRetryStatus(request(HttpMethod.GET), 503, 1)).isTrue();
        assertThat(policy.shouldRetryStatus(request(HttpMethod.GET), 429, 1)).isTrue();
        // 500 is excluded on purpose: it is a server-side bug that will fail identically every time.
        assertThat(policy.shouldRetryStatus(request(HttpMethod.GET), 500, 1)).isFalse();
        assertThat(policy.shouldRetryStatus(request(HttpMethod.GET), 400, 1)).isFalse();
    }

    @Test
    void doesNotRetryAPostUnlessItOptsIn() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertThat(policy.shouldRetryStatus(request(HttpMethod.POST), 503, 1)).isFalse();
        assertThat(policy.shouldRetryStatus(
                JiraRequest.builder(HttpMethod.POST, ANY).retryable(true).build(), 503, 1)).isTrue();
    }

    @Test
    void stopsAtTheAttemptBudget() {
        RetryPolicy policy = RetryPolicy.builder().maxAttempts(2).build();

        assertThat(policy.shouldRetryStatus(request(HttpMethod.GET), 503, 1)).isTrue();
        assertThat(policy.shouldRetryStatus(request(HttpMethod.GET), 503, 2)).isFalse();
    }

    @Test
    void growsTheBackoffExponentiallyAndCapsIt() {
        RetryPolicy policy = RetryPolicy.builder()
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(300))
                .jitter(false)
                .build();

        assertThat(policy.backoff(1, Optional.empty())).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.backoff(2, Optional.empty())).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.backoff(3, Optional.empty())).isEqualTo(Duration.ofMillis(300));
    }

    @Test
    void drawsTheDelayFromTheWholeIntervalWhenJitterIsOn() {
        RetryPolicy policy = RetryPolicy.builder().initialBackoff(Duration.ofMillis(100)).build();

        for (int i = 0; i < 100; i++) {
            assertThat(policy.backoff(1, Optional.empty()))
                    .isBetween(Duration.ZERO, Duration.ofMillis(100));
        }
    }

    @Test
    void honoursTheServersRetryAfterButNotBeyondTheCeiling() {
        RetryPolicy policy = RetryPolicy.builder().maxBackoff(Duration.ofSeconds(20)).build();

        assertThat(policy.backoff(1, Optional.of(Duration.ofSeconds(5)))).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.backoff(1, Optional.of(Duration.ofHours(1)))).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void rejectsAnImpossibleAttemptBudget() {
        assertThatThrownBy(() -> RetryPolicy.builder().maxAttempts(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverRetriesWhenTheBudgetIsOne() {
        assertThat(RetryPolicy.none().shouldRetryStatus(request(HttpMethod.GET), 503, 1)).isFalse();
        assertThat(RetryPolicy.none().shouldRetryFailure(request(HttpMethod.GET), 1)).isFalse();
    }
}
