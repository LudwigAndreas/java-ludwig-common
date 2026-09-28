package ru.ludwigandreas.observability.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import ru.ludwigandreas.observability.config.ObservabilityEnvironmentPostProcessor;

/** The defaults that make a service observable without configuring anything - and never override it. */
class ObservabilityEnvironmentPostProcessorTest {

    private final ObservabilityEnvironmentPostProcessor postProcessor = new ObservabilityEnvironmentPostProcessor();

    @Test
    void splitsLivenessFromReadinessSoAnUnreadyServiceIsNotKilledAndRestarted() {
        MockEnvironment environment = process(new MockEnvironment());

        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.health.livenessstate.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.health.readinessstate.enabled")).isEqualTo("true");
    }

    @Test
    void exposesOperationalEndpointsButNothingThatDisclosesConfiguration() {
        String exposed = process(new MockEnvironment()).getProperty("management.endpoints.web.exposure.include");

        assertThat(exposed).contains("health", "prometheus", "loggers");
        // env, configprops and heapdump disclose credentials and memory contents; a starter must not
        // be the reason they became reachable.
        assertThat(exposed).doesNotContain("env", "configprops", "heapdump", "threaddump");
    }

    @Test
    void turnsOnKafkaObservationWhichSpringShipsDisabled() {
        MockEnvironment environment = process(new MockEnvironment());

        assertThat(environment.getProperty("spring.kafka.template.observation-enabled")).isEqualTo("true");
        assertThat(environment.getProperty("spring.kafka.listener.observation-enabled")).isEqualTo("true");
    }

    @Test
    void enablesGracefulShutdownSoADeployDoesNotLookLikeAnIncident() {
        MockEnvironment environment = process(new MockEnvironment());

        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
    }

    @Test
    void derivesTheServiceNameFromTheApplicationName() {
        MockEnvironment environment = process(new MockEnvironment().withProperty("spring.application.name", "catalog"));

        assertThat(environment.getProperty("ludwig.observability.service.name")).isEqualTo("catalog");
    }

    @Test
    void publishesTheIdentityAsResourceAttributesUsingBracketedMapKeys() {
        MockEnvironment environment = process(new MockEnvironment().withProperty("spring.application.name", "catalog"));

        // Bracket notation keeps the dots inside the map key; a dotted suffix would leave the binder
        // guessing where the property path ends and the key begins.
        assertThat(environment.getProperty("management.opentelemetry.resource-attributes[service.name]"))
                .isEqualTo("catalog");
    }

    @Test
    void neverOverridesAValueTheServiceHasSetItself() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("application", Map.of(
                "spring.application.name", "catalog",
                "ludwig.observability.service.name", "catalog-api",
                "server.shutdown", "immediate")));

        process(environment);

        assertThat(environment.getProperty("server.shutdown")).isEqualTo("immediate");
        assertThat(environment.getProperty("ludwig.observability.service.name")).isEqualTo("catalog-api");
        // And the resource attributes follow the value the service chose, rather than the derived one -
        // otherwise the traces and the metrics would describe two different services.
        assertThat(environment.getProperty("management.opentelemetry.resource-attributes[service.name]"))
                .isEqualTo("catalog-api");
    }

    @Test
    void addsNothingWhenTheModuleIsDisabled() {
        MockEnvironment environment = process(
                new MockEnvironment().withProperty("ludwig.observability.enabled", "false"));

        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isNull();
    }

    @Test
    void isIdempotentWhenTheSameEnvironmentIsProcessedTwice() {
        MockEnvironment environment = process(new MockEnvironment());
        long before = environment.getPropertySources().stream().count();

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources().stream().count()).isEqualTo(before);
    }

    private MockEnvironment process(MockEnvironment environment) {
        postProcessor.postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }
}
