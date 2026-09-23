package ru.ludwigandreas.restclient.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.restclient.annotation.EnableLudwigRestClients;
import ru.ludwigandreas.restclient.error.RestClientResponseException;
import ru.ludwigandreas.restclient.spi.FallbackContext;
import ru.ludwigandreas.restclient.spi.FallbackHandler;

/**
 * Fallbacks: what they answer for, and - more importantly - what they refuse to answer for.
 */
class FallbackIntegrationTest {

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
    @DisplayName("a 5xx that outlives the retry budget is answered by the fallback")
    void fallsBackOnServerError() {
        server.enqueue(new MockResponse().setResponseCode(503));

        RestClientTestSupport.runner(props())
                .withUserConfiguration(FallbackConfiguration.class)
                .run(context -> {
                    BillingApi api = context.getBean(BillingApi.class);

                    assertThat(api.invoice("42")).isEqualTo("cached");

                    RecordingFallback fallback = context.getBean(RecordingFallback.class);
                    assertThat(fallback.contexts).hasSize(1);
                    FallbackContext seen = fallback.contexts.get(0);
                    assertThat(seen.clientName()).isEqualTo("billing");
                    assertThat(seen.method()).isEqualTo("GET");
                    assertThat(seen.uriTemplate()).isEqualTo("/invoices/{id}");
                    assertThat(seen.interfaceMethod().getName()).isEqualTo("invoice");
                    assertThat(seen.returnType()).isEqualTo(String.class);
                    assertThat(seen.failure()).isInstanceOf(RestClientResponseException.class);
                });
    }

    @Test
    @DisplayName("a 4xx is NOT answered by the fallback - the peer answered, and the answer stands")
    void doesNotFallBackOnClientError() {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));

        RestClientTestSupport.runner(props())
                .withUserConfiguration(FallbackConfiguration.class)
                .run(context -> {
                    BillingApi api = context.getBean(BillingApi.class);

                    assertThatThrownBy(() -> api.invoice("42"))
                            .isInstanceOf(RestClientResponseException.class)
                            .satisfies(failure -> assertThat(
                                    ((RestClientResponseException) failure).getStatusCode())
                                    .isEqualTo(403));

                    assertThat(context.getBean(RecordingFallback.class).contexts).isEmpty();
                });
    }

    @Test
    @DisplayName("without a configured fallback the proxy is the bare one and the exception surfaces")
    void noFallbackConfiguredMeansNoWrapper() {
        server.enqueue(new MockResponse().setResponseCode(503));

        String[] properties = RestClientTestSupport.props(RestClientTestSupport.fastClient(
                "billing", server.url("/").toString(),
                "ludwig.rest-client.clients.billing.resilience.retry.max-attempts=1"));

        RestClientTestSupport.runner(properties)
                .withUserConfiguration(InterfacesOnly.class)
                .run(context -> assertThatThrownBy(() -> context.getBean(BillingApi.class).invoice("42"))
                        .isInstanceOf(RestClientResponseException.class));
    }

    private String[] props() {
        return RestClientTestSupport.props(RestClientTestSupport.fastClient(
                "billing", server.url("/").toString(),
                "ludwig.rest-client.clients.billing.resilience.retry.max-attempts=1",
                "ludwig.rest-client.clients.billing.resilience.fallback.handler=recordingFallback"));
    }

    /** Answers with stale data and records what it was told about the failure. */
    static class RecordingFallback implements FallbackHandler {

        private final List<FallbackContext> contexts = new CopyOnWriteArrayList<>();

        @Override
        public Object fallback(FallbackContext context) {
            contexts.add(context);
            return "cached";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableLudwigRestClients(basePackageClasses = BillingApi.class)
    static class InterfacesOnly {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableLudwigRestClients(basePackageClasses = BillingApi.class)
    static class FallbackConfiguration {

        @Bean
        RecordingFallback recordingFallback() {
            return new RecordingFallback();
        }
    }
}
