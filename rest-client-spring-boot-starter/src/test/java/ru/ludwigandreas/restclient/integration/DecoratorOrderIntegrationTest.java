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
import ru.ludwigandreas.restclient.error.RestClientCallNotPermittedException;

/**
 * Pins the decorator order: {@code RateLimiter -> Bulkhead -> CircuitBreaker -> Retry -> call}.
 *
 * <p>Order is not directly observable, so each test constructs a situation whose outcome differs
 * between the documented order and the plausible wrong one. That is the only way to test an
 * ordering, and it also documents why the order is what it is.
 */
class DecoratorOrderIntegrationTest {

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
    @DisplayName("the rate limiter sits outside the retry: one permit covers all three attempts")
    void rateLimiterIsOutsideTheRetry() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        // One permit per minute. Inside the retry, attempt two would be refused immediately; outside
        // it, the permit is taken once for the logical call and all three attempts proceed.
        String[] properties = RestClientTestSupport.props(RestClientTestSupport.fastClient(
                "billing", server.url("/").toString(),
                "ludwig.rest-client.clients.billing.resilience.retry.max-attempts=3",
                "ludwig.rest-client.clients.billing.resilience.rate-limiter.enabled=true",
                "ludwig.rest-client.clients.billing.resilience.rate-limiter.limit-for-period=1",
                "ludwig.rest-client.clients.billing.resilience.rate-limiter.limit-refresh-period=60s",
                "ludwig.rest-client.clients.billing.resilience.rate-limiter.timeout-duration=0"));

        RestClientTestSupport.runner(properties).run(context -> {
            String body = context.getBean(RestClientRegistry.class).rest("billing")
                    .get().uri("/x").retrieve().body(String.class);

            assertThat(body).isEqualTo("ok");
            assertThat(server.getRequestCount()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("the rate limiter is outermost: a second logical call is refused, not queued")
    void rateLimiterRefusesTheSecondCall() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        String[] properties = RestClientTestSupport.props(RestClientTestSupport.fastClient(
                "billing", server.url("/").toString(),
                "ludwig.rest-client.clients.billing.resilience.retry.max-attempts=1",
                "ludwig.rest-client.clients.billing.resilience.rate-limiter.enabled=true",
                "ludwig.rest-client.clients.billing.resilience.rate-limiter.limit-for-period=1",
                "ludwig.rest-client.clients.billing.resilience.rate-limiter.limit-refresh-period=60s",
                "ludwig.rest-client.clients.billing.resilience.rate-limiter.timeout-duration=0"));

        RestClientTestSupport.runner(properties).run(context -> {
            var client = context.getBean(RestClientRegistry.class).rest("billing");
            assertThat(client.get().uri("/x").retrieve().body(String.class)).isEqualTo("ok");

            assertThatThrownBy(() -> client.get().uri("/x").retrieve().body(String.class))
                    .isInstanceOf(RestClientCallNotPermittedException.class)
                    .satisfies(failure -> assertThat(
                            ((RestClientCallNotPermittedException) failure).getPolicy())
                            .isEqualTo("rate-limiter"));

            // Refused before the transport, so the second call never reached the server.
            assertThat(server.getRequestCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("the breaker sits outside the retry: once open, no attempt is made at all")
    void breakerIsOutsideTheRetry() {
        // Two logical calls of two attempts each is four failures, which fills the window.
        for (int i = 0; i < 4; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }

        String[] properties = RestClientTestSupport.props(RestClientTestSupport.fastClient(
                "billing", server.url("/").toString(),
                "ludwig.rest-client.clients.billing.resilience.retry.max-attempts=2",
                "ludwig.rest-client.clients.billing.resilience.circuit-breaker.enabled=true",
                "ludwig.rest-client.clients.billing.resilience.circuit-breaker.sliding-window-size=2",
                "ludwig.rest-client.clients.billing.resilience.circuit-breaker"
                        + ".minimum-number-of-calls=2",
                "ludwig.rest-client.clients.billing.resilience.circuit-breaker"
                        + ".failure-rate-threshold=50",
                "ludwig.rest-client.clients.billing.resilience.circuit-breaker"
                        + ".wait-duration-in-open-state=60s"));

        RestClientTestSupport.runner(properties).run(context -> {
            var client = context.getBean(RestClientRegistry.class).rest("billing");
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> client.get().uri("/x").retrieve().body(String.class))
                        .isInstanceOf(RuntimeException.class);
            }
            int calls = server.getRequestCount();
            assertThat(calls).isEqualTo(4);

            assertThatThrownBy(() -> client.get().uri("/x").retrieve().body(String.class))
                    .isInstanceOf(RestClientCallNotPermittedException.class);

            // The retry that would have happened inside the breaker did not happen either: an open
            // breaker stops the amplification, which is the reason retry is nested inside it.
            assertThat(server.getRequestCount()).isEqualTo(calls);
        });
    }
}
