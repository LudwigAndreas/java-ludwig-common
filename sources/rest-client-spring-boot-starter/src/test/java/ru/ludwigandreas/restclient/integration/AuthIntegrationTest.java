package ru.ludwigandreas.restclient.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.restclient.core.RestClientRegistry;
import ru.ludwigandreas.restclient.error.RestClientAuthenticationException;
import ru.ludwigandreas.restclient.error.RestClientResponseException;
import ru.ludwigandreas.restclient.spi.TokenSupplier;

/** Credentials on the wire, and the one extra attempt a rejected credential is allowed. */
class AuthIntegrationTest {

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
    @DisplayName("a 401 triggers a forced refresh and exactly one more attempt")
    void refreshesAndRetriesOnceAfterUnauthorized() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RestClientTestSupport.runner(bearerProps())
                .withUserConfiguration(RotatingTokens.class)
                .run(context -> {
                    String body = context.getBean(RestClientRegistry.class).rest("billing")
                            .get().uri("/x").retrieve().body(String.class);

                    assertThat(body).isEqualTo("ok");
                    assertThat(server.getRequestCount()).isEqualTo(2);
                });

        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertThat(first.getHeader("Authorization")).isEqualTo("Bearer token-1");
        // A different token, because the authenticator was told the previous one was rejected.
        assertThat(second.getHeader("Authorization")).isEqualTo("Bearer token-2");
    }

    @Test
    @DisplayName("a second 401 is a real authorization failure and is reported as the response it is")
    void doesNotLoopOnRepeatedUnauthorized() {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(401));

        RestClientTestSupport.runner(bearerProps())
                .withUserConfiguration(RotatingTokens.class)
                .run(context -> assertThatThrownBy(() ->
                        context.getBean(RestClientRegistry.class).rest("billing")
                                .get().uri("/x").retrieve().body(String.class))
                        .isInstanceOf(RestClientResponseException.class)
                        .satisfies(failure -> assertThat(
                                ((RestClientResponseException) failure).getStatusCode()).isEqualTo(401)));

        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a credential that cannot be obtained fails the call and never calls anonymously")
    void failsClosedWhenNoCredentialIsAvailable() {
        RestClientTestSupport.runner(bearerProps())
                .withUserConfiguration(NoTokens.class)
                .run(context -> assertThatThrownBy(() ->
                        context.getBean(RestClientRegistry.class).rest("billing")
                                .get().uri("/x").retrieve().body(String.class))
                        .isInstanceOf(RestClientAuthenticationException.class)
                        .satisfies(failure -> assertThat(
                                ((RestClientAuthenticationException) failure).getAuthType())
                                .isEqualTo("bearer")));

        // The important half of the assertion: nothing was sent.
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("a static api-key is sent on every request in the configured header")
    void sendsApiKeyHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        String[] properties = RestClientTestSupport.props(RestClientTestSupport.fastClient(
                "billing", server.url("/").toString(),
                "ludwig.rest-client.clients.billing.auth.type=api-key",
                "ludwig.rest-client.clients.billing.auth.key=k-123",
                "ludwig.rest-client.clients.billing.auth.header-name=X-Partner-Key"));

        RestClientTestSupport.runner(properties).run(context ->
                context.getBean(RestClientRegistry.class).rest("billing")
                        .get().uri("/x").retrieve().body(String.class));

        assertThat(server.takeRequest().getHeader("X-Partner-Key")).isEqualTo("k-123");
    }

    private String[] bearerProps() {
        return RestClientTestSupport.props(RestClientTestSupport.fastClient(
                "billing", server.url("/").toString(),
                "ludwig.rest-client.clients.billing.auth.type=bearer",
                "ludwig.rest-client.clients.billing.auth.token-supplier=rotatingTokens",
                "ludwig.rest-client.clients.billing.resilience.retry.max-attempts=1"));
    }

    /** A supplier that mints a new token whenever it is told the previous one was rejected. */
    @Configuration(proxyBeanMethods = false)
    static class RotatingTokens {

        @Bean
        TokenSupplier rotatingTokens() {
            AtomicInteger generation = new AtomicInteger(1);
            return (client, forceRefresh) -> "token-"
                    + (forceRefresh ? generation.incrementAndGet() : generation.get());
        }
    }

    /** A supplier that has nothing to give, which must fail the call rather than send nothing. */
    @Configuration(proxyBeanMethods = false)
    static class NoTokens {

        @Bean
        TokenSupplier rotatingTokens() {
            return (client, forceRefresh) -> null;
        }
    }
}
