package ru.ludwigandreas.observability.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import ru.ludwigandreas.observability.config.ObservabilityEnvironmentPostProcessor;

/**
 * Proves the claim the environment post-processor is built on: that the bracketed keys it writes are
 * bound by Spring Boot into the OpenTelemetry {@link Resource} every exported span carries.
 *
 * <p>Worth a test of its own because the failure is invisible. A key the binder does not recognise is
 * simply not bound - no warning, no error - and the result is spans that arrive at the backend with
 * no service name, discovered only when somebody tries to filter by one.
 */
class ResourceAttributeBindingTest {

    @Test
    void bindsEveryIdentityAttributeTheEnvironmentPostProcessorWrites() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "catalog")
                .withProperty("ludwig.observability.service.namespace", "commerce")
                .withProperty("ludwig.observability.service.version", "1.4.2")
                .withProperty("ludwig.observability.service.environment", "prod")
                .withProperty("ludwig.observability.service.instance", "catalog-7d9f");
        new ObservabilityEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpenTelemetryAutoConfiguration.class))
                .withPropertyValues(resourceAttributeProperties(environment))
                .run(context -> {
                    Resource resource = context.getBean(Resource.class);

                    assertThat(attribute(resource, "service.name")).isEqualTo("catalog");
                    assertThat(attribute(resource, "service.namespace")).isEqualTo("commerce");
                    assertThat(attribute(resource, "service.version")).isEqualTo("1.4.2");
                    assertThat(attribute(resource, "service.instance.id")).isEqualTo("catalog-7d9f");
                    assertThat(attribute(resource, "deployment.environment")).isEqualTo("prod");
                });
    }

    @Test
    void fallsBackToBootsOwnDefaultWhenNoIdentityIsConfigured() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpenTelemetryAutoConfiguration.class))
                .run(context -> assertThat(attribute(context.getBean(Resource.class), "service.name"))
                        .isEqualTo("unknown_service"));
    }

    /** Re-reads the bracketed keys the post-processor produced, exactly as written. */
    private String[] resourceAttributeProperties(MockEnvironment environment) {
        return new String[] {
            "management.opentelemetry.resource-attributes[service.name]="
                    + environment.getProperty("management.opentelemetry.resource-attributes[service.name]"),
            "management.opentelemetry.resource-attributes[service.namespace]="
                    + environment.getProperty("management.opentelemetry.resource-attributes[service.namespace]"),
            "management.opentelemetry.resource-attributes[service.version]="
                    + environment.getProperty("management.opentelemetry.resource-attributes[service.version]"),
            "management.opentelemetry.resource-attributes[service.instance.id]="
                    + environment.getProperty("management.opentelemetry.resource-attributes[service.instance.id]"),
            "management.opentelemetry.resource-attributes[deployment.environment]="
                    + environment.getProperty("management.opentelemetry.resource-attributes[deployment.environment]"),
        };
    }

    private String attribute(Resource resource, String key) {
        return resource.getAttribute(AttributeKey.stringKey(key));
    }
}
