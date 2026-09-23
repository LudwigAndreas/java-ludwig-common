package ru.ludwigandreas.jira.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.jira.JiraClient;
import ru.ludwigandreas.jira.error.JiraApiException;
import ru.ludwigandreas.jira.error.JiraAuthenticationException;
import ru.ludwigandreas.jira.error.JiraNotFoundException;
import ru.ludwigandreas.jira.error.JiraRateLimitException;
import ru.ludwigandreas.jira.error.JiraTransportException;
import ru.ludwigandreas.jira.http.RetryPolicy;
import ru.ludwigandreas.jira.model.user.JiraUser;

class JiraRestClientTest {

    private static final String MYSELF = "{\"name\":\"svc-jira\",\"displayName\":\"Service Account\"}";

    private JiraClient clientFor(RecordingTransport transport) {
        return clientFor(transport, RetryPolicy.none());
    }

    private JiraClient clientFor(RecordingTransport transport, RetryPolicy retryPolicy) {
        return JiraClient.builder()
                .baseUrl("https://jira.example.com/jira")
                .personalAccessToken("secret-token")
                .transport(transport)
                .retryPolicy(retryPolicy)
                .build();
    }

    @Test
    void keepsTheContextPathWhenResolvingAnEndpoint() {
        RecordingTransport transport = new RecordingTransport().respondWith(200, MYSELF);

        try (JiraClient client = clientFor(transport)) {
            client.users().myself();
        }

        assertThat(transport.lastRequest().uri())
                .hasToString("https://jira.example.com/jira/rest/api/2/myself");
    }

    @Test
    void sendsTheBearerTokenAndTheXsrfHeaderOnEveryRequest() {
        RecordingTransport transport = new RecordingTransport().respondWith(200, MYSELF);

        try (JiraClient client = clientFor(transport)) {
            client.users().myself();
        }

        assertThat(transport.lastRequest().headers())
                .containsEntry("Authorization", "Bearer secret-token")
                .containsEntry("X-Atlassian-Token", "no-check")
                .containsEntry("Accept", "application/json");
    }

    @Test
    void sendsBasicCredentialsWhenThatSchemeWasChosen() {
        RecordingTransport transport = new RecordingTransport().respondWith(200, MYSELF);

        try (JiraClient client = JiraClient.builder()
                .baseUrl("https://jira.example.com")
                .basicAuth("svc", "p@ss word")
                .transport(transport)
                .build()) {
            client.users().myself();
        }

        String expected = "Basic " + Base64.getEncoder().encodeToString("svc:p@ss word".getBytes());
        assertThat(transport.lastRequest().headers()).containsEntry("Authorization", expected);
    }

    @Test
    void percentEncodesPathSegmentsSoAKeyCannotEscapeItsPosition() {
        RecordingTransport transport = new RecordingTransport().respondWith(200, "{\"key\":\"X\"}");

        try (JiraClient client = clientFor(transport)) {
            client.issues().find("../../admin");
        }

        assertThat(transport.lastRequest().uri().toString())
                .isEqualTo("https://jira.example.com/jira/rest/api/2/issue/..%2F..%2Fadmin");
    }

    @Test
    void encodesQueryValuesWithPercentTwentyRatherThanPlus() {
        RecordingTransport transport = new RecordingTransport().respondWith(200, "[]");

        try (JiraClient client = clientFor(transport)) {
            client.rest().get("/rest/api/2/thing").query("q", "a b").as(List.class);
        }

        assertThat(transport.lastRequest().uri().getRawQuery()).isEqualTo("q=a%20b");
    }

    @Test
    void mapsEachFailureStatusOntoItsOwnException() {
        assertThatThrownBy(() -> call(401, "{}")).isInstanceOf(JiraAuthenticationException.class);
        assertThatThrownBy(() -> call(404, "{}")).isInstanceOf(JiraNotFoundException.class);
        assertThatThrownBy(() -> call(429, "{}")).isInstanceOf(JiraRateLimitException.class);
        assertThatThrownBy(() -> call(500, "{}")).isInstanceOf(JiraApiException.class);
    }

    @Test
    void carriesJirasFieldErrorsOntoTheException() {
        RecordingTransport transport = new RecordingTransport().respondWith(400,
                "{\"errorMessages\":[\"boom\"],\"errors\":{\"summary\":\"Summary is required.\"}}");

        try (JiraClient client = clientFor(transport)) {
            assertThatThrownBy(() -> client.users().myself())
                    .isInstanceOf(JiraApiException.class)
                    .satisfies(thrown -> {
                        JiraApiException api = (JiraApiException) thrown;
                        assertThat(api.getStatus()).isEqualTo(400);
                        assertThat(api.getErrorMessages()).containsExactly("boom");
                        assertThat(api.getFieldErrors()).containsEntry("summary", "Summary is required.");
                    })
                    .hasMessageContaining("Summary is required.");
        }
    }

    @Test
    void survivesAnErrorBodyThatIsNotAJiraErrorCollection() {
        RecordingTransport transport = new RecordingTransport().respondWith(502, "<html>Bad Gateway</html>");

        try (JiraClient client = clientFor(transport)) {
            assertThatThrownBy(() -> client.users().myself())
                    .isInstanceOf(JiraApiException.class)
                    .hasMessageContaining("Bad Gateway");
        }
    }

    @Test
    void turnsA404IntoAnEmptyOptionalWithoutSwallowingA403() {
        try (JiraClient notFound = clientFor(new RecordingTransport().respondWith(404, "{}"))) {
            assertThat(notFound.issues().find("ABC-1")).isEqualTo(Optional.empty());
        }
        try (JiraClient forbidden = clientFor(new RecordingTransport().respondWith(403, "{}"))) {
            assertThatThrownBy(() -> forbidden.issues().find("ABC-1")).isInstanceOf(JiraApiException.class);
        }
    }

    @Test
    void retriesAnIdempotentReadOnATransientStatusAndReturnsTheEventualSuccess() {
        RecordingTransport transport = new RecordingTransport()
                .respondWith(503, "{}")
                .respondWith(200, MYSELF);

        try (JiraClient client = clientFor(transport, fastRetries())) {
            JiraUser user = client.users().myself();
            assertThat(user.name()).isEqualTo("svc-jira");
        }

        assertThat(transport.requests()).hasSize(2);
    }

    @Test
    void doesNotRetryAWriteBecauseJiraOffersNoIdempotencyKey() {
        RecordingTransport transport = new RecordingTransport().respondWith(503, "{}");

        try (JiraClient client = clientFor(transport, fastRetries())) {
            assertThatThrownBy(() -> client.comments().add("ABC-1", "hello"))
                    .isInstanceOf(JiraApiException.class);
        }

        assertThat(transport.requests()).hasSize(1);
    }

    @Test
    void retriesSearchEvenThoughItIsAPostBecauseItIsAReadInDisguise() {
        RecordingTransport transport = new RecordingTransport()
                .respondWith(503, "{}")
                .respondWith(200, "{\"startAt\":0,\"maxResults\":50,\"total\":0,\"issues\":[]}");

        try (JiraClient client = clientFor(transport, fastRetries())) {
            assertThat(client.search().search(
                    ru.ludwigandreas.jira.jql.JqlQuery.of("project = OPS")).issues()).isEmpty();
        }

        assertThat(transport.requests()).hasSize(2);
    }

    @Test
    void neverRetriesA401BecauseRepeatingItLocksTheAccountOut() {
        RecordingTransport transport = new RecordingTransport().respondWith(401, "{}");

        try (JiraClient client = clientFor(transport, fastRetries())) {
            assertThatThrownBy(() -> client.users().myself())
                    .isInstanceOf(JiraAuthenticationException.class);
        }

        assertThat(transport.requests()).hasSize(1);
    }

    @Test
    void retriesATransportFailureOnAReadAndGivesUpAfterTheBudget() {
        RecordingTransport transport = new RecordingTransport()
                .failWith(new JiraTransportException("connection reset", new java.io.IOException()))
                .failWith(new JiraTransportException("connection reset", new java.io.IOException()));

        try (JiraClient client = clientFor(transport, RetryPolicy.builder()
                .maxAttempts(2)
                .initialBackoff(Duration.ZERO)
                .jitter(false)
                .build())) {
            assertThatThrownBy(() -> client.users().myself()).isInstanceOf(JiraTransportException.class);
        }

        assertThat(transport.requests()).hasSize(2);
    }

    @Test
    void reportsTheServersRetryAfterOnTheRateLimitException() {
        RecordingTransport transport = new RecordingTransport()
                .respondWith(429, "{}", Map.of("Retry-After", List.of("7")));

        try (JiraClient client = clientFor(transport)) {
            assertThatThrownBy(() -> client.users().myself())
                    .isInstanceOfSatisfying(JiraRateLimitException.class,
                            thrown -> assertThat(thrown.getRetryAfter()).contains(Duration.ofSeconds(7)));
        }
    }

    @Test
    void findsARetryAfterHeaderWhateverCaseTheProxySentItIn() {
        RecordingTransport transport = new RecordingTransport()
                .respondWith(429, "{}", Map.of("retry-after", List.of("3")));

        try (JiraClient client = clientFor(transport)) {
            assertThatThrownBy(() -> client.users().myself())
                    .isInstanceOfSatisfying(JiraRateLimitException.class,
                            thrown -> assertThat(thrown.getRetryAfter()).contains(Duration.ofSeconds(3)));
        }
    }

    @Test
    void acceptsTheHttpDateFormOfRetryAfterAndNeverReturnsANegativeDelay() {
        RecordingTransport transport = new RecordingTransport()
                .respondWith(429, "{}", Map.of("Retry-After", List.of("Wed, 21 Oct 2015 07:28:00 GMT")));

        try (JiraClient client = clientFor(transport)) {
            assertThatThrownBy(() -> client.users().myself())
                    .isInstanceOfSatisfying(JiraRateLimitException.class,
                            thrown -> assertThat(thrown.getRetryAfter()).contains(Duration.ZERO));
        }
    }

    @Test
    void ignoresAnUnparseableRetryAfterRatherThanFailingOnIt() {
        RecordingTransport transport = new RecordingTransport()
                .respondWith(429, "{}", Map.of("Retry-After", List.of("soon")));

        try (JiraClient client = clientFor(transport)) {
            assertThatThrownBy(() -> client.users().myself())
                    .isInstanceOfSatisfying(JiraRateLimitException.class,
                            thrown -> assertThat(thrown.getRetryAfter()).isEmpty());
        }
    }

    @Test
    void closesTheTransportWithTheClient() {
        RecordingTransport transport = new RecordingTransport();

        new JiraClientCloser(clientFor(transport)).closeIt();

        assertThat(transport.isClosed()).isTrue();
    }

    private static RetryPolicy fastRetries() {
        return RetryPolicy.builder().maxAttempts(3).initialBackoff(Duration.ZERO).jitter(false).build();
    }

    private void call(int status, String body) {
        try (JiraClient client = clientFor(new RecordingTransport().respondWith(status, body))) {
            client.users().myself();
        }
    }

    /** Wrapper so the close-propagation test does not rely on try-with-resources it is asserting about. */
    private record JiraClientCloser(JiraClient client) {

        void closeIt() {
            client.close();
        }
    }
}
