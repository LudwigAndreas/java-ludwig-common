package ru.ludwigandreas.restclient.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import ru.ludwigandreas.restclient.annotation.EnableLudwigRestClients;
import ru.ludwigandreas.restclient.core.RestClientRegistry;
import ru.ludwigandreas.restclient.reactiveapi.ReactiveBillingApi;
import ru.ludwigandreas.restclient.error.RestClientCallNotPermittedException;
import ru.ludwigandreas.restclient.error.RestClientResponseException;

/**
 * The parity claim: an {@code async} client makes the same decisions as a {@code sync} one.
 *
 * <p>Each test here has a counterpart in {@link RetryIntegrationTest} or
 * {@link ResilienceIntegrationTest} with the same configuration and the same expectation. If the two
 * pipelines ever drift, one of these pairs stops agreeing.
 */
class ReactiveParityIntegrationTest {

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
    @DisplayName("a 503 is retried, exactly as it is on a sync client")
    void retriesRetryableStatus() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RestClientTestSupport.runner(props("resilience.retry.max-attempts=3")).run(context -> {
            String body = reactive(context).get().uri("/things").retrieve()
                    .bodyToMono(String.class).block(Duration.ofSeconds(5));

            assertThat(body).isEqualTo("ok");
            assertThat(server.getRequestCount()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("a POST is not retried without an opt-in, exactly as on a sync client")
    void doesNotRetryPostByDefault() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("created"));

        RestClientTestSupport.runner(props("resilience.retry.max-attempts=3")).run(context -> {
            assertThatThrownBy(() -> reactive(context).post().uri("/things").bodyValue("{}")
                    .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5)))
                    .isInstanceOf(RestClientResponseException.class);

            assertThat(server.getRequestCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("a failed response becomes the same typed exception, carrying the client name")
    void producesTheSameTypedException() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("nope"));

        RestClientTestSupport.runner(props("resilience.retry.max-attempts=1")).run(context ->
                assertThatThrownBy(() -> reactive(context).get().uri("/things").retrieve()
                        .bodyToMono(String.class).block(Duration.ofSeconds(5)))
                        .isInstanceOf(RestClientResponseException.class)
                        .satisfies(failure -> {
                            RestClientResponseException response = (RestClientResponseException) failure;
                            assertThat(response.getStatusCode()).isEqualTo(404);
                            assertThat(response.getClientName()).isEqualTo("pricing");
                        }));
    }

    @Test
    @DisplayName("the breaker opens and refuses without calling, exactly as on a sync client")
    void breakerOpensAndRefuses() {
        for (int i = 0; i < 4; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }

        String[] properties = props(
                "resilience.retry.max-attempts=1",
                "resilience.circuit-breaker.enabled=true",
                "resilience.circuit-breaker.sliding-window-size=4",
                "resilience.circuit-breaker.minimum-number-of-calls=4",
                "resilience.circuit-breaker.failure-rate-threshold=50",
                "resilience.circuit-breaker.wait-duration-in-open-state=5s");

        RestClientTestSupport.runner(properties).run(context -> {
            WebClient client = reactive(context);
            for (int i = 0; i < 4; i++) {
                assertThatThrownBy(() -> client.get().uri("/x").retrieve()
                        .bodyToMono(String.class).block(Duration.ofSeconds(5)))
                        .isInstanceOf(RestClientResponseException.class);
            }
            int callsBeforeOpening = server.getRequestCount();

            assertThatThrownBy(() -> client.get().uri("/x").retrieve()
                    .bodyToMono(String.class).block(Duration.ofSeconds(5)))
                    .isInstanceOf(RestClientCallNotPermittedException.class);

            assertThat(server.getRequestCount()).isEqualTo(callsBeforeOpening);
        });
    }

    @Test
    @DisplayName("a declarative interface on an async client returns a Mono and works")
    void declarativeInterfaceReturnsAMono() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("price-7"));

        RestClientTestSupport.runner(props("resilience.retry.max-attempts=1"))
                .withUserConfiguration(EnableReactiveInterfaces.class)
                .run(context -> {
                    ReactiveBillingApi api = context.getBean(ReactiveBillingApi.class);
                    assertThat(api.price("7").block(Duration.ofSeconds(5))).isEqualTo("price-7");
                });
    }

    private WebClient reactive(org.springframework.context.ApplicationContext context) {
        return context.getBean(RestClientRegistry.class).reactive("pricing");
    }

    private String[] props(String... settings) {
        String[] extra = new String[settings.length + 1];
        extra[0] = "ludwig.rest-client.clients.pricing.mode=async";
        for (int i = 0; i < settings.length; i++) {
            extra[i + 1] = "ludwig.rest-client.clients.pricing." + settings[i];
        }
        return RestClientTestSupport.props(
                RestClientTestSupport.fastClient("pricing", server.url("/").toString(), extra));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableLudwigRestClients(basePackageClasses = ReactiveBillingApi.class)
    static class EnableReactiveInterfaces {
    }
}
