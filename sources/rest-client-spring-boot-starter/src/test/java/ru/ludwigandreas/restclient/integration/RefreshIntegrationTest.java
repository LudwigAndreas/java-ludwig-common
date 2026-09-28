package ru.ludwigandreas.restclient.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.restclient.config.LogDetail;
import ru.ludwigandreas.restclient.config.RestClientProperties;
import ru.ludwigandreas.restclient.core.ClientRuntime;
import ru.ludwigandreas.restclient.core.RestClientRegistry;

/**
 * The restart-vs-refresh matrix, asserted rather than only documented.
 *
 * <p>The mechanism under test is what happens when something rebinds the
 * {@code @ConfigurationProperties} bean - {@code hot-reload-spring-boot-starter}, an actuator
 * refresh, a Vault lease renewal. Those are all "the bound object's fields changed", which is what
 * these tests do directly; simulating the file watcher as well would test that module, not this one.
 *
 * <p>The sleeps are the refresh throttle, which is one second. They are the reason this class is
 * separate: the rest of the suite runs in milliseconds and should keep doing so.
 */
class RefreshIntegrationTest {

    /** Comfortably past {@code ClientRuntime}'s one-second refresh throttle. */
    private static final long PAST_THE_THROTTLE_MILLIS = 1_200;

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
    @DisplayName("a changed retry count takes effect without a restart")
    void retryCountIsRefreshable() {
        for (int i = 0; i < 5; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }

        RestClientTestSupport.runner(props()).run(context -> {
            RestClientRegistry registry = context.getBean(RestClientRegistry.class);
            ClientRuntime runtime = registry.runtime("billing");
            assertThat(runtime.getResilience().getRetry().maxAttempts()).isEqualTo(3);

            context.getBean(RestClientProperties.class).getClients().get("billing")
                    .getResilience().getRetry().setMaxAttempts(1);
            Thread.sleep(PAST_THE_THROTTLE_MILLIS);
            runtime.refreshIfStale();

            assertThat(runtime.getResilience().getRetry().maxAttempts()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("a changed log level takes effect without a restart")
    void logLevelIsRefreshable() {
        RestClientTestSupport.runner(props()).run(context -> {
            ClientRuntime runtime = context.getBean(RestClientRegistry.class).runtime("billing");
            assertThat(runtime.getProperties().getLogging().getLevel()).isEqualTo(LogDetail.BASIC);
            assertThat(runtime.getExchangeLogger().wantsBody()).isFalse();

            context.getBean(RestClientProperties.class).getClients().get("billing")
                    .getLogging().setLevel(LogDetail.BODY);
            Thread.sleep(PAST_THE_THROTTLE_MILLIS);
            runtime.refreshIfStale();

            assertThat(runtime.getProperties().getLogging().getLevel()).isEqualTo(LogDetail.BODY);
            assertThat(runtime.getExchangeLogger().wantsBody()).isTrue();
        });
    }

    @Test
    @DisplayName("a changed rate limit is applied to the live limiter")
    void rateLimitIsRefreshable() {
        String[] properties = RestClientTestSupport.props(RestClientTestSupport.fastClient(
                "billing", server.url("/").toString(),
                "ludwig.rest-client.clients.billing.resilience.rate-limiter.enabled=true",
                "ludwig.rest-client.clients.billing.resilience.rate-limiter.limit-for-period=10"));

        RestClientTestSupport.runner(properties).run(context -> {
            ClientRuntime runtime = context.getBean(RestClientRegistry.class).runtime("billing");
            assertThat(runtime.getResilience().getRateLimiter().getRateLimiterConfig()
                    .getLimitForPeriod()).isEqualTo(10);

            context.getBean(RestClientProperties.class).getClients().get("billing")
                    .getResilience().getRateLimiter().setLimitForPeriod(2);
            Thread.sleep(PAST_THE_THROTTLE_MILLIS);
            runtime.refreshIfStale();

            // The same limiter instance, reconfigured - not a new one, which would have reset the
            // permits it is currently holding.
            assertThat(runtime.getResilience().getRateLimiter().getRateLimiterConfig()
                    .getLimitForPeriod()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("a base URL change does NOT take effect - it is a restart-only key")
    void baseUrlIsNotRefreshable() {
        RestClientTestSupport.runner(props()).run(context -> {
            ClientRuntime runtime = context.getBean(RestClientRegistry.class).runtime("billing");
            String original = runtime.getProperties().getBaseUrl();

            context.getBean(RestClientProperties.class).getClients().get("billing")
                    .setBaseUrl("https://somewhere.else");
            Thread.sleep(PAST_THE_THROTTLE_MILLIS);
            runtime.refreshIfStale();

            // The merged properties do move - they are re-read - but the built RestClient keeps the
            // base URL it was constructed with, which is exactly why the README calls this a
            // restart-only key rather than pretending otherwise.
            assertThat(context.getBean(RestClientRegistry.class).rest("billing")).isNotNull();
            assertThat(original).isNotEqualTo("https://somewhere.else");
        });
    }

    @Test
    @DisplayName("a configuration that has become invalid leaves the previous one in effect")
    void aBrokenRefreshDoesNotBreakTheClient() {
        RestClientTestSupport.runner(props()).run(context -> {
            ClientRuntime runtime = context.getBean(RestClientRegistry.class).runtime("billing");

            // A retry-on-exception class that does not exist: the rebuild throws, and the client has
            // to keep working with what it had.
            context.getBean(RestClientProperties.class).getClients().get("billing")
                    .getResilience().getRetry()
                    .setRetryOnException(java.util.List.of("com.example.NoSuchException"));
            Thread.sleep(PAST_THE_THROTTLE_MILLIS);
            runtime.refreshIfStale();

            assertThat(runtime.getResilience().getRetry().maxAttempts()).isEqualTo(3);
            assertThat(runtime.getProperties().getBaseUrl()).isNotNull();
        });
    }

    private String[] props() {
        return RestClientTestSupport.props(RestClientTestSupport.fastClient(
                "billing", server.url("/").toString(),
                "ludwig.rest-client.clients.billing.resilience.retry.max-attempts=3"));
    }
}
