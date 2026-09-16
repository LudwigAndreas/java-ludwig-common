package ru.ludwigandreas.observability.config;

import io.micrometer.tracing.exporter.SpanExportingPredicate;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import ru.ludwigandreas.observability.tracing.ForceableSampler;
import ru.ludwigandreas.observability.tracing.PathExcludingSpanExportingPredicate;
import ru.ludwigandreas.observability.tracing.TraceContextAccessor;

/**
 * The two things this module adds on top of Spring Boot's own OTLP tracing: a sampler that can be
 * overridden per request, and an export filter that keeps health probes out of the tracing backend.
 *
 * <p>Everything else about tracing - the exporter, the propagators, the bridge to Micrometer, the
 * span processor - is Boot's, and deliberately left alone. This module's job is to configure that
 * machinery for an estate rather than to replace it, so the pieces here are the ones Boot has no
 * opinion on.
 */
@AutoConfiguration(
        before = OpenTelemetryAutoConfiguration.class,
        // beforeName, not before: web-core is an optional dependency, and naming its class directly
        // here would make this annotation unresolvable in a service that does not have it. The
        // ordering matters because the TraceIdProvider below is @ConditionalOnMissingBean, and
        // web-core's own MDC-based default is too - whichever autoconfiguration runs second is the
        // one that backs off.
        beforeName = "ru.ludwigandreas.webcore.config.WebCoreProblemAutoConfiguration")
@ConditionalOnClass(Sampler.class)
@ConditionalOnProperty(
        name = {"ludwig.observability.enabled", "ludwig.observability.tracing.enabled"},
        matchIfMissing = true)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityTracingAutoConfiguration {

    /** Boot's own default, restated so this sampler behaves identically when nothing forces sampling. */
    private static final float DEFAULT_SAMPLING_PROBABILITY = 0.1f;

    /**
     * Replaces Boot's sampler with one that honours the force-trace header.
     *
     * <p>Declared {@code before} {@link OpenTelemetryAutoConfiguration}, whose own {@code otelSampler}
     * is {@code @ConditionalOnMissingBean} and therefore backs off once this exists. Winning by
     * ordering rather than by overriding means the two never both define a sampler, which would be a
     * startup failure rather than a silent precedence question.
     *
     * <p>The delegate is built to be exactly what Boot would have built - parent-based over a
     * trace-id ratio - so switching this module on does not silently change how much a service
     * samples. The probability is read from Boot's own property, not a new one: an operator tuning
     * {@code management.tracing.sampling.probability} must not find it ignored because a starter
     * introduced a second name for it.
     */
    @Bean
    @ConditionalOnMissingBean(Sampler.class)
    @ConditionalOnProperty(
            prefix = "ludwig.observability.tracing.sampling", name = "enabled", matchIfMissing = true)
    public Sampler ludwigForceableSampler(Environment environment) {
        float probability = environment.getProperty(
                "management.tracing.sampling.probability", Float.class, DEFAULT_SAMPLING_PROBABILITY);
        return new ForceableSampler(Sampler.parentBased(Sampler.traceIdRatioBased(probability)));
    }

    /**
     * Keeps excluded paths - health probes, by default - out of the tracing backend.
     *
     * <p>Boot collects every {@link SpanExportingPredicate} bean into the span processor, so this
     * needs only to exist; it composes with any predicate the application declares rather than
     * displacing it.
     */
    @Bean
    @ConditionalOnMissingBean(name = "ludwigPathExcludingSpanExportingPredicate")
    public SpanExportingPredicate ludwigPathExcludingSpanExportingPredicate(ObservabilityProperties properties) {
        return new PathExcludingSpanExportingPredicate(properties.getTracing().getExcludedPaths());
    }

    /**
     * Hands web-core's problem documents the real trace id when both starters are present.
     *
     * <p>Nested and guarded on the interface, because web-core is an optional dependency: the
     * enclosing class must stay loadable in a service that has this starter and not that one.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ru.ludwigandreas.webcore.trace.TraceIdProvider")
    public static class WebCoreTraceIdConfiguration {

        /**
         * Declared {@code @ConditionalOnMissingBean} on the interface so a service that has
         * deliberately written its own provider keeps it. web-core's own MDC-based default is
         * registered by its autoconfiguration, which is also conditional on the interface being
         * missing - and the {@code beforeName} on the enclosing class is what guarantees this one is
         * evaluated first.
         */
        @Bean
        @ConditionalOnMissingBean(ru.ludwigandreas.webcore.trace.TraceIdProvider.class)
        public ru.ludwigandreas.webcore.trace.TraceIdProvider ludwigTraceIdProvider(TraceContextAccessor traceContext) {
            return new ru.ludwigandreas.observability.tracing.MicrometerTraceIdProvider(traceContext);
        }
    }
}
