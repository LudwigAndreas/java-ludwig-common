package ru.ludwigandreas.restclient.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.ludwigandreas.audit.config.AuditCoreAutoConfiguration;
import ru.ludwigandreas.restclient.config.RestClientAuthAutoConfiguration;
import ru.ludwigandreas.restclient.config.RestClientAutoConfiguration;

/**
 * The startup validator, which is the module's promise that a misconfigured client fails the
 * deployment rather than the first real request at 2am.
 */
class ConfigurationValidatorTest {

    @Test
    @DisplayName("a client with no base-url is refused")
    void refusesAClientWithoutABaseUrl() {
        runner("ludwig.rest-client.clients.billing.read-timeout=5s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("has no base-url"));
    }

    @Test
    @DisplayName("a base-url in the defaults block is refused - it is wrong for all but one client")
    void refusesASharedBaseUrl() {
        runner("ludwig.rest-client.defaults.base-url=https://shared.internal",
                "ludwig.rest-client.clients.billing.base-url=https://billing.internal")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("defaults.base-url"));
    }

    @Test
    @DisplayName("credentials over plain HTTP are refused, unless the host is loopback")
    void refusesCredentialsOverCleartext() {
        runner("ludwig.rest-client.clients.billing.base-url=http://billing.internal",
                "ludwig.rest-client.clients.billing.auth.type=basic",
                "ludwig.rest-client.clients.billing.auth.username=alice",
                "ludwig.rest-client.clients.billing.auth.password=secret")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("plain HTTP"));

        runner("ludwig.rest-client.clients.billing.base-url=http://localhost:8080",
                "ludwig.rest-client.clients.billing.auth.type=basic",
                "ludwig.rest-client.clients.billing.auth.username=alice",
                "ludwig.rest-client.clients.billing.auth.password=secret")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("a retry budget too small for two attempts is refused, because max-attempts would lie")
    void refusesAnUnreachableRetryBudget() {
        runner("ludwig.rest-client.clients.billing.base-url=https://billing.internal",
                "ludwig.rest-client.clients.billing.read-timeout=20s",
                "ludwig.rest-client.clients.billing.resilience.retry.max-attempts=3",
                "ludwig.rest-client.clients.billing.resilience.retry.max-elapsed-time=5s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("max-elapsed-time"));
    }

    @Test
    @DisplayName("trust-all without the second switch is refused")
    void refusesTrustAllWithoutTheGuard() {
        runner("ludwig.rest-client.clients.billing.base-url=https://billing.internal",
                "ludwig.rest-client.clients.billing.tls.trust-all=true")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("allow-trust-all"));
    }

    @Test
    @DisplayName("trust-all with both switches is refused anyway while a production profile is active")
    void refusesTrustAllInProduction() {
        runner("spring.profiles.active=prod",
                "ludwig.rest-client.allow-trust-all=true",
                "ludwig.rest-client.clients.billing.base-url=https://billing.internal",
                "ludwig.rest-client.clients.billing.tls.trust-all=true")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("production"));
    }

    @Test
    @DisplayName("trust-all with both switches and no production profile is allowed")
    void allowsTrustAllWhenDeliberate() {
        runner("ludwig.rest-client.allow-trust-all=true",
                "ludwig.rest-client.clients.billing.base-url=https://billing.internal",
                "ludwig.rest-client.clients.billing.tls.trust-all=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("a time limiter on a sync client is refused, because nothing can enforce it")
    void refusesTimeLimiterOnSyncClients() {
        runner("ludwig.rest-client.clients.billing.base-url=https://billing.internal",
                "ludwig.rest-client.clients.billing.resilience.time-limiter.enabled=true")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("time-limiter"));
    }

    @Test
    @DisplayName("a thread-pool bulkhead on an async client is refused")
    void refusesThreadPoolBulkheadOnAsyncClients() {
        runner("ludwig.rest-client.clients.pricing.base-url=https://pricing.internal",
                "ludwig.rest-client.clients.pricing.mode=async",
                "ludwig.rest-client.clients.pricing.resilience.bulkhead.enabled=true",
                "ludwig.rest-client.clients.pricing.resilience.bulkhead.type=thread_pool")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("THREAD_POOL"));
    }

    @Test
    @DisplayName("a breaker whose minimum call count exceeds its window is refused - it can never open")
    void refusesABreakerThatCanNeverOpen() {
        runner("ludwig.rest-client.clients.billing.base-url=https://billing.internal",
                "ludwig.rest-client.clients.billing.resilience.circuit-breaker.sliding-window-size=10",
                "ludwig.rest-client.clients.billing.resilience.circuit-breaker"
                        + ".minimum-number-of-calls=50")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("can never open"));
    }

    @Test
    @DisplayName("token relay without a resource server is refused - every call would fail closed")
    void refusesTokenRelayWithoutAResourceServer() {
        runner("ludwig.rest-client.clients.billing.base-url=https://billing.internal",
                "ludwig.rest-client.clients.billing.auth.type=oauth2-token-relay",
                "ludwig.rest-client.clients.billing.auth.relay-enabled=true")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("resource server"));
    }

    @Test
    @DisplayName("an unknown auth.type is refused with the list of known types")
    void refusesAnUnknownAuthType() {
        runner("ludwig.rest-client.clients.billing.base-url=https://billing.internal",
                "ludwig.rest-client.clients.billing.auth.type=magic")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause()
                        .hasMessageContaining("unknown auth.type")
                        .hasMessageContaining("bearer"));
    }

    @Test
    @DisplayName("bearer with both a token and a supplier is refused rather than resolved by a rule")
    void refusesAmbiguousBearerConfiguration() {
        runner("ludwig.rest-client.clients.billing.base-url=https://billing.internal",
                "ludwig.rest-client.clients.billing.auth.type=bearer",
                "ludwig.rest-client.clients.billing.auth.token=abc",
                "ludwig.rest-client.clients.billing.auth.token-supplier=mySupplier")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("both set"));
    }

    @Test
    @DisplayName("reactor-netty on a sync client is refused - it would block an event loop")
    void refusesReactorNettyForSyncClients() {
        runner("ludwig.rest-client.clients.billing.base-url=https://billing.internal",
                "ludwig.rest-client.clients.billing.transport=reactor-netty")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("event loop"));
    }

    @Test
    @DisplayName("a client asking for a transport that is not on the classpath is refused")
    void refusesAMissingTransport() {
        new ApplicationContextRunner()
                .withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(
                        "org.apache.hc.client5.http.impl.classic.HttpClients"))
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        RestClientAutoConfiguration.class, RestClientAuthAutoConfiguration.class,
                        AuditCoreAutoConfiguration.class))
                .withPropertyValues("ludwig.rest-client.clients.billing.base-url=https://billing.internal",
                        "ludwig.rest-client.clients.billing.transport=apache")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("httpclient5"));
    }

    private ApplicationContextRunner runner(String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        RestClientAutoConfiguration.class, RestClientAuthAutoConfiguration.class,
                        AuditCoreAutoConfiguration.class))
                .withPropertyValues(properties);
    }
}
