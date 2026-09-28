package ru.ludwigandreas.observability.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.observability.config.ObservabilityCoreAutoConfiguration;
import ru.ludwigandreas.observability.config.ObservabilityMetricsAutoConfiguration;
import ru.ludwigandreas.observability.config.ObservabilityTracingAutoConfiguration;
import ru.ludwigandreas.observability.config.ObservabilityWebAutoConfiguration;
import ru.ludwigandreas.observability.correlation.CorrelationContext;
import ru.ludwigandreas.observability.core.ServiceIdentity;
import ru.ludwigandreas.observability.tracing.ForceableSampler;
import ru.ludwigandreas.observability.tracing.TraceContextAccessor;
import ru.ludwigandreas.observability.web.CorrelationIdFilter;
import ru.ludwigandreas.observability.web.ForceSamplingHintFilter;

/** What a service actually gets - and does not get - by putting this starter on its classpath. */
class ObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservabilityCoreAutoConfiguration.class,
                    ObservabilityTracingAutoConfiguration.class,
                    ObservabilityMetricsAutoConfiguration.class));

    /**
     * Meter filters are only applied to a registry by Spring Boot's own MeterRegistryPostProcessor,
     * so a registry registered as a plain bean would silently ignore every filter here - and the
     * tests would pass while production did nothing. These runs therefore stand up Boot's metrics
     * autoconfiguration and let it build the registry, which is what a real service does.
     */
    private final ApplicationContextRunner metricsRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MetricsAutoConfiguration.class,
                    CompositeMeterRegistryAutoConfiguration.class,
                    SimpleMetricsExportAutoConfiguration.class,
                    ObservabilityCoreAutoConfiguration.class,
                    ObservabilityMetricsAutoConfiguration.class));

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservabilityCoreAutoConfiguration.class,
                    ObservabilityWebAutoConfiguration.class));

    @Test
    void registersTheCoreBeansWithNoConfigurationAtAll() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(ServiceIdentity.class)
                .hasSingleBean(CorrelationContext.class)
                .hasSingleBean(TraceContextAccessor.class));
    }

    @Test
    void worksWithoutATracerBecauseTracingCanBeSwitchedOff() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // Correlation, JSON logging and metrics must all survive management.tracing.enabled=false.
            assertThat(context.getBean(TraceContextAccessor.class).currentTraceId()).isEmpty();
        });
    }

    @Test
    void installsASamplerThatCanBeForcedPerRequest() {
        runner.run(context -> assertThat(context.getBean(Sampler.class)).isInstanceOf(ForceableSampler.class));
    }

    @Test
    void readsTheSamplingProbabilityFromSpringBootsOwnPropertyRatherThanANewName() {
        runner.withPropertyValues("management.tracing.sampling.probability=1.0")
                .run(context -> assertThat(context.getBean(Sampler.class).getDescription())
                        .contains("1.0"));
    }

    @Test
    void registersTheProbeSpanExclusion() {
        runner.run(context -> assertThat(context).hasSingleBean(SpanExportingPredicate.class));
    }

    @Test
    void registersTheCorrelationFilterInAServletApplication() {
        webRunner.run(context -> assertThat(filterRegistrations(context, CorrelationIdFilter.class)).hasSize(1));
    }

    @Test
    void doesNotExposeForceSamplingUntilADeploymentSaysItsEdgeCanBeTrusted() {
        // The header lets any caller make this service trace 100% of its traffic into a per-span
        // billed backend, so it stays absent until someone opts in.
        webRunner.run(context -> assertThat(filterRegistrations(context, ForceSamplingHintFilter.class)).isEmpty());

        webRunner.withPropertyValues("ludwig.observability.tracing.sampling.trusted-force-header=true")
                .run(context -> assertThat(filterRegistrations(context, ForceSamplingHintFilter.class)).hasSize(1));
    }

    @Test
    void registersTheMeterFiltersThatBoundCardinalityAndShapeLatency() {
        metricsRunner.run(context -> assertThat(context)
                        .hasBean("ludwigCommonTagsMeterFilter")
                        .hasBean("ludwigUriCardinalityMeterFilter")
                        .hasBean("ludwigIgnoredPathsMeterFilter")
                        .hasBean("ludwigHttpHistogramMeterFilter"));
    }

    @Test
    void tagsEveryMeterWithTheServiceIdentity() {
        metricsRunner.withPropertyValues(
                        "ludwig.observability.service.name=catalog",
                        "ludwig.observability.service.environment=prod")
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    registry.counter("orders.placed").increment();

                    assertThat(registry.get("orders.placed").counter().getId().getTag("service"))
                            .isEqualTo("catalog");
                    assertThat(registry.get("orders.placed").counter().getId().getTag("environment"))
                            .isEqualTo("prod");
                });
    }

    @Test
    void capsUriCardinalityEndToEndThroughTheRegistry() {
        metricsRunner.withPropertyValues("ludwig.observability.metrics.http.max-uri-tags=3")
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    for (int i = 0; i < 50; i++) {
                        registry.counter("http.server.requests", "uri", "/probe-" + i).increment();
                    }

                    assertThat(registry.find("http.server.requests").counters()).hasSize(4);
                });
    }

    @Test
    void keepsProbeTrafficOutOfTheLatencyDistribution() {
        metricsRunner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            registry.counter("http.server.requests", "uri", "/actuator/health/liveness").increment();
            registry.counter("http.server.requests", "uri", "/orders/{id}").increment();

            assertThat(registry.find("http.server.requests").counters()).hasSize(1);
        });
    }

    @Test
    void failsAtStartupRatherThanPerRequestOnANonPositiveCorrelationLengthCap() {
        // The alternative is silent: every inbound id is rejected, a fresh one is generated per hop,
        // and from inside any single service that is indistinguishable from callers not sending ids.
        runner.withPropertyValues("ludwig.observability.correlation.max-length=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessageContaining("max-length must be positive"));
    }

    @Test
    void failsAtStartupOnAnInvalidCorrelationPattern() {
        runner.withPropertyValues("ludwig.observability.correlation.allowed-pattern=[unclosed")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsAtStartupRatherThanErasingEveryPerEndpointMetric() {
        metricsRunner.withPropertyValues("ludwig.observability.metrics.http.max-uri-tags=-1")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessageContaining("max-uri-tags must not be negative"));
    }

    @Test
    void backsOffEntirelyWhenTheModuleIsDisabled() {
        runner.withPropertyValues("ludwig.observability.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ServiceIdentity.class)
                        .doesNotHaveBean(CorrelationContext.class));
    }

    @Test
    void yieldsToAnApplicationThatDeclaresItsOwnBeans() {
        runner.withUserConfiguration(OwnCorrelationContext.class)
                .run(context -> assertThat(context.getBean(CorrelationContext.class).mdcKey())
                        .isEqualTo("requestId"));
    }

    @Test
    void leavesSpringBootsSamplerInPlaceWhenTheOverrideIsSwitchedOff() {
        runner.withPropertyValues("ludwig.observability.tracing.sampling.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(Sampler.class));
    }

    private <T extends jakarta.servlet.Filter> java.util.Collection<?> filterRegistrations(
            org.springframework.context.ApplicationContext context, Class<T> filterType) {
        return context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .filter(registration -> filterType.isInstance(registration.getFilter()))
                .toList();
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnCorrelationContext {

        @Bean
        CorrelationContext correlationContext() {
            return new CorrelationContext("requestId");
        }
    }
}
