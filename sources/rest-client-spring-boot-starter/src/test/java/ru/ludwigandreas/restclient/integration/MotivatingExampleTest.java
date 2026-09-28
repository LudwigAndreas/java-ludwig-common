package ru.ludwigandreas.restclient.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import ru.ludwigandreas.restclient.config.ClientMode;
import ru.ludwigandreas.restclient.config.TransportEngine;
import ru.ludwigandreas.restclient.core.ClientRuntime;
import ru.ludwigandreas.restclient.core.RestClientRegistry;

/**
 * The configuration from the specification, loaded as YAML, asserted property by property.
 *
 * <p>It is a separate test from the rest because it pins the <em>example</em> rather than a
 * behaviour: everything a reader copies out of the README has to keep meaning what it says, and the
 * failure mode of a documentation example is that it quietly stops working while the docs keep
 * claiming it does.
 *
 * <p>The only change from the specification's text is the {@code billing} authentication type: it
 * reads {@code oauth2-client-credentials} there, which needs a Spring Security
 * {@code ClientRegistrationRepository} and a live authorization server, neither of which belongs in
 * a test that is about property binding. The dimension being asserted - that two clients differ in
 * every setting - is unaffected.
 */
class MotivatingExampleTest {

    @Test
    @DisplayName("the specification's two-client example binds to exactly what it says")
    void bindsTheSpecificationExample() throws IOException {
        RestClientTestSupport.runner()
                .withInitializer(yaml("motivating-example.yaml"))
                .run(context -> {
                    RestClientRegistry registry = context.getBean(RestClientRegistry.class);
                    assertThat(registry.names()).containsExactlyInAnyOrder("billing", "pricing");

                    ClientRuntime billing = registry.runtime("billing");
                    ClientRuntime pricing = registry.runtime("pricing");

                    // Inherited from defaults by both.
                    assertThat(billing.getProperties().getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(pricing.getProperties().getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));

                    // Overridden per client, in opposite directions.
                    assertThat(billing.getProperties().getReadTimeout()).isEqualTo(Duration.ofSeconds(120));
                    assertThat(pricing.getProperties().getReadTimeout()).isEqualTo(Duration.ofMillis(800));

                    // The defaults block's retry reaches billing untouched and is overridden by pricing.
                    assertThat(billing.getResilience().getRetry().maxAttempts()).isEqualTo(3);
                    assertThat(pricing.getResilience().getRetry().maxAttempts()).isEqualTo(1);

                    // A bulkhead block with no `enabled` key still produces a bulkhead of 20.
                    assertThat(billing.getResilience().getBulkhead()).isNotNull();
                    assertThat(billing.getResilience().getBulkhead().getBulkheadConfig()
                            .getMaxConcurrentCalls()).isEqualTo(20);
                    // ...and pricing, which configured none, has none.
                    assertThat(pricing.getResilience().getBulkhead()).isNull();

                    // The defaults block's breaker settings reach both.
                    assertThat(billing.getResilience().getCircuitBreaker().getCircuitBreakerConfig()
                            .getSlidingWindowSize()).isEqualTo(100);
                    assertThat(pricing.getResilience().getCircuitBreaker().getCircuitBreakerConfig()
                            .getSlidingWindowSize()).isEqualTo(100);

                    // Neither declared a mode or a transport, so both take the built-in defaults.
                    assertThat(billing.getProperties().getMode()).isEqualTo(ClientMode.SYNC);
                    assertThat(billing.getProperties().getTransport()).isEqualTo(TransportEngine.HTTP_CLIENT);

                    // Both are injectable, under their own names.
                    assertThat(context).hasBean("billingRestClient").hasBean("pricingRestClient");
                });
    }

    private ApplicationContextInitializer<ConfigurableApplicationContext> yaml(String resource)
            throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load(resource, new ClassPathResource(resource));
        return context -> sources.forEach(source ->
                context.getEnvironment().getPropertySources().addFirst(source));
    }
}
