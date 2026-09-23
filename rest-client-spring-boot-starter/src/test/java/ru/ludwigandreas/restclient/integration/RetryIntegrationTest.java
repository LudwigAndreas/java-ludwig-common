package ru.ludwigandreas.restclient.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.restclient.core.RestClientRegistry;
import ru.ludwigandreas.restclient.error.RestClientResponseException;

/** Retry, its budget, and {@code Retry-After}, against a server that answers differently each time. */
class RetryIntegrationTest {

    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("a 503 is retried and the second attempt's answer is returned")
    void retriesRetryableStatus() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RestClientTestSupport.runner(props("max-attempts=3")).run(context -> {
            String body = rest(context).get().uri("/things").retrieve().body(String.class);

            assertThat(body).isEqualTo("ok");
            assertThat(server.getRequestCount()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("a 404 is not retried - the peer answered and the answer will not change")
    void doesNotRetryClientErrors() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("nope"));

        RestClientTestSupport.runner(props("max-attempts=3")).run(context ->
                assertThatThrownBy(() -> rest(context).get().uri("/things").retrieve().body(String.class))
                        .isInstanceOf(RestClientResponseException.class)
                        .satisfies(failure -> {
                            RestClientResponseException response = (RestClientResponseException) failure;
                            assertThat(response.getStatusCode()).isEqualTo(404);
                            assertThat(response.getClientName()).isEqualTo("billing");
                            assertThat(response.getBodySnippet()).contains("nope");
                        }));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a spent attempt budget surfaces the last failure, not a generic error")
    void exhaustsTheAttemptBudget() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        RestClientTestSupport.runner(props("max-attempts=2")).run(context ->
                assertThatThrownBy(() -> rest(context).get().uri("/things").retrieve().body(String.class))
                        .isInstanceOf(RestClientResponseException.class)
                        .hasMessageContaining("503"));
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a POST is not retried without an opt-in, and is retried with one")
    void retriesPostOnlyWhenAskedTo() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("created"));

        RestClientTestSupport.runner(props("max-attempts=3")).run(context -> {
            assertThatThrownBy(() -> rest(context).post().uri("/things").body("{}")
                    .retrieve().body(String.class))
                    .isInstanceOf(RestClientResponseException.class);
            assertThat(server.getRequestCount()).isEqualTo(1);

            String body = rest(context).post().uri("/things")
                    .header("X-Ludwig-Retry", "true")
                    .body("{}")
                    .retrieve().body(String.class);

            assertThat(body).isEqualTo("created");
            assertThat(server.getRequestCount()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("Retry-After is honoured in place of the computed backoff")
    void honoursRetryAfter() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "1"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RestClientTestSupport.runner(props("max-attempts=2", "max-elapsed-time=30s")).run(context -> {
            long start = System.nanoTime();
            String body = rest(context).get().uri("/things").retrieve().body(String.class);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertThat(body).isEqualTo("ok");
            // The configured backoff is 10ms; only the header can explain a wait near a second.
            assertThat(elapsedMillis).isGreaterThanOrEqualTo(900);
        });
    }

    @Test
    @DisplayName("X-Ludwig-* headers are consumed and never reach the peer")
    void stripsControlHeaders() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RestClientTestSupport.runner(props("max-attempts=3")).run(context ->
                rest(context).get().uri("/things")
                        .header("X-Ludwig-Retry", "true")
                        .header("X-Ludwig-Max-Attempts", "1")
                        .retrieve().body(String.class));

        var recorded = server.takeRequest();
        assertThat(recorded.getHeader("X-Ludwig-Retry")).isNull();
        assertThat(recorded.getHeader("X-Ludwig-Max-Attempts")).isNull();
    }

    @Test
    @DisplayName("X-Ludwig-Max-Attempts can lower the attempt limit but not raise it")
    void perRequestAttemptLimitOnlyLowers() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        RestClientTestSupport.runner(props("max-attempts=2")).run(context -> {
            assertThatThrownBy(() -> rest(context).get().uri("/things")
                    .header("X-Ludwig-Max-Attempts", "10")
                    .retrieve().body(String.class))
                    .isInstanceOf(RestClientResponseException.class);

            assertThat(server.getRequestCount()).isEqualTo(2);
        });
    }

    private org.springframework.web.client.RestClient rest(
            org.springframework.context.ApplicationContext context) {
        return context.getBean(RestClientRegistry.class).rest("billing");
    }

    private String[] props(String... retrySettings) {
        String base = "ludwig.rest-client.clients.billing.resilience.retry.";
        String[] extra = new String[retrySettings.length];
        for (int i = 0; i < retrySettings.length; i++) {
            extra[i] = base + retrySettings[i];
        }
        return RestClientTestSupport.props(
                RestClientTestSupport.fastClient("billing", server.url("/").toString(), extra));
    }
}
