package ru.ludwigandreas.restclient.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestClient;
import ru.ludwigandreas.restclient.core.RestClientRegistry;
import ru.ludwigandreas.restclient.error.RestClientCallNotPermittedException;
import ru.ludwigandreas.restclient.error.RestClientResponseException;
import ru.ludwigandreas.restclient.error.RestClientTimeoutException;

/** The circuit breaker, the bulkhead, and the promise that two clients cannot interfere. */
class ResilienceIntegrationTest {

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
    @DisplayName("the breaker opens after the configured failures and then refuses without calling")
    void breakerOpensAndRefusesWithoutCalling() {
        for (int i = 0; i < 4; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }

        RestClientTestSupport.runner(breakerProps()).run(context -> {
            RestClient client = rest(context);
            for (int i = 0; i < 4; i++) {
                assertThatThrownBy(() -> client.get().uri("/x").retrieve().body(String.class))
                        .isInstanceOf(RestClientResponseException.class);
            }
            int callsBeforeOpening = server.getRequestCount();

            assertThatThrownBy(() -> client.get().uri("/x").retrieve().body(String.class))
                    .isInstanceOf(RestClientCallNotPermittedException.class)
                    .satisfies(failure -> assertThat(
                            ((RestClientCallNotPermittedException) failure).getPolicy())
                            .isEqualTo("circuit-breaker"));

            // The whole point: the refused call never reached the server.
            assertThat(server.getRequestCount()).isEqualTo(callsBeforeOpening);
        });
    }

    @Test
    @DisplayName("the breaker closes again once the dependency answers")
    void breakerRecovers() throws Exception {
        for (int i = 0; i < 4; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }
        for (int i = 0; i < 4; i++) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        }

        RestClientTestSupport.runner(breakerProps()).run(context -> {
            RestClient client = rest(context);
            for (int i = 0; i < 4; i++) {
                assertThatThrownBy(() -> client.get().uri("/x").retrieve().body(String.class))
                        .isInstanceOf(RestClientResponseException.class);
            }
            assertThatThrownBy(() -> client.get().uri("/x").retrieve().body(String.class))
                    .isInstanceOf(RestClientCallNotPermittedException.class);

            // wait-duration-in-open-state is 200ms in this configuration.
            Thread.sleep(400);

            assertThat(client.get().uri("/x").retrieve().body(String.class)).isEqualTo("ok");
        });
    }

    @Test
    @DisplayName("a 4xx does not open the breaker, because the peer is healthy")
    void clientErrorsDoNotOpenTheBreaker() {
        for (int i = 0; i < 6; i++) {
            server.enqueue(new MockResponse().setResponseCode(422).setBody("invalid"));
        }

        RestClientTestSupport.runner(breakerProps()).run(context -> {
            RestClient client = rest(context);
            for (int i = 0; i < 6; i++) {
                assertThatThrownBy(() -> client.get().uri("/x").retrieve().body(String.class))
                        .isInstanceOf(RestClientResponseException.class);
            }
            // Six failures in a row, and the sixth still reached the server.
            assertThat(server.getRequestCount()).isEqualTo(6);
        });
    }

    @Test
    @DisplayName("a full bulkhead rejects rather than queueing, and says which policy refused")
    void bulkheadRejectsWhenFull() throws Exception {
        for (int i = 0; i < 10; i++) {
            // setHeadersDelay, not setBodyDelay: the pipeline's permit is held for the exchange, and
            // the exchange ends when the status line and headers arrive. A body that trickles in
            // afterwards is read by the caller, outside the pipeline - which is documented, and is
            // what makes a delayed body the wrong way to keep a bulkhead permit busy.
            server.enqueue(new MockResponse().setResponseCode(200).setBody("ok")
                    .setHeadersDelay(400, TimeUnit.MILLISECONDS));
        }

        String[] properties = RestClientTestSupport.props(RestClientTestSupport.fastClient(
                "billing", server.url("/").toString(),
                "ludwig.rest-client.clients.billing.resilience.bulkhead.enabled=true",
                "ludwig.rest-client.clients.billing.resilience.bulkhead.max-concurrent-calls=1",
                "ludwig.rest-client.clients.billing.resilience.bulkhead.max-wait-duration=0"));

        RestClientTestSupport.runner(properties).run(context -> {
            RestClient client = rest(context);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch started = new CountDownLatch(1);
            AtomicInteger rejected = new AtomicInteger();
            try {
                pool.submit(() -> {
                    started.countDown();
                    return client.get().uri("/x").retrieve().body(String.class);
                });
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(100);

                for (int i = 0; i < 3; i++) {
                    try {
                        client.get().uri("/x").retrieve().body(String.class);
                    } catch (RestClientCallNotPermittedException ex) {
                        assertThat(ex.getPolicy()).isEqualTo("bulkhead");
                        rejected.incrementAndGet();
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(rejected.get()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("two clients in one context have genuinely independent timeouts")
    void clientsHaveIndependentTimeouts() throws IOException {
        MockWebServer slow = new MockWebServer();
        slow.start();
        try {
            slow.enqueue(new MockResponse().setResponseCode(200).setBody("slow")
                    .setHeadersDelay(600, TimeUnit.MILLISECONDS));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("fast")
                    .setHeadersDelay(600, TimeUnit.MILLISECONDS));

            String[] properties = RestClientTestSupport.props(
                    RestClientTestSupport.fastClient("billing", slow.url("/").toString(),
                            "ludwig.rest-client.clients.billing.read-timeout=3s",
                            "ludwig.rest-client.clients.billing.resilience.retry.max-attempts=1"),
                    RestClientTestSupport.fastClient("pricing", server.url("/").toString(),
                            "ludwig.rest-client.clients.pricing.read-timeout=100ms",
                            "ludwig.rest-client.clients.pricing.resilience.retry.max-attempts=1"));

            RestClientTestSupport.runner(properties).run(context -> {
                RestClientRegistry registry = context.getBean(RestClientRegistry.class);

                assertThat(registry.rest("billing").get().uri("/x").retrieve().body(String.class))
                        .isEqualTo("slow");
                assertThatThrownBy(() -> registry.rest("pricing").get().uri("/x")
                        .retrieve().body(String.class))
                        .isInstanceOf(RestClientTimeoutException.class);
            });
        } finally {
            slow.shutdown();
        }
    }

    private String[] breakerProps() {
        return RestClientTestSupport.props(RestClientTestSupport.fastClient(
                "billing", server.url("/").toString(),
                "ludwig.rest-client.clients.billing.resilience.retry.max-attempts=1",
                "ludwig.rest-client.clients.billing.resilience.circuit-breaker.enabled=true",
                "ludwig.rest-client.clients.billing.resilience.circuit-breaker.sliding-window-size=4",
                "ludwig.rest-client.clients.billing.resilience.circuit-breaker.minimum-number-of-calls=4",
                "ludwig.rest-client.clients.billing.resilience.circuit-breaker.failure-rate-threshold=50",
                "ludwig.rest-client.clients.billing.resilience.circuit-breaker"
                        + ".wait-duration-in-open-state=200ms"));
    }

    private RestClient rest(ApplicationContext context) {
        return context.getBean(RestClientRegistry.class).rest("billing");
    }
}
