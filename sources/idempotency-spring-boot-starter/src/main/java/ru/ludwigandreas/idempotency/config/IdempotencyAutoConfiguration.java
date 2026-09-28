package ru.ludwigandreas.idempotency.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.idempotency.api.RequestFingerprint;
import ru.ludwigandreas.idempotency.metrics.IdempotencyMetrics;
import ru.ludwigandreas.idempotency.metrics.MicrometerIdempotencyMetrics;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * The half of this module that needs no database: the fingerprinter, the metrics binding and this
 * module's contribution of error text to {@code web-core}'s pipeline.
 *
 * <p>Separate from {@link IdempotencyPersistenceAutoConfiguration} for the reason every starter here
 * splits: a service that only wants to fingerprint requests, or that brings its own
 * {@code IdempotencyStore}, should not get a startup failure about a {@code DataSource} it does not have.
 *
 * <p>It contributes no {@code Clock} bean and no {@code ObjectMapper} bean, both for the reason recorded
 * in {@code audit-spring-boot-starter}: a bean of a common type guarded by
 * {@code @ConditionalOnMissingBean} is not safe in a library, because two autoconfigurations with no
 * ordering between them each see no bean and each register one, and anything injecting that type by
 * type then fails to start on an ambiguity neither module caused alone. This module takes an
 * {@code ObjectProvider} and falls back.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ludwig.idempotency", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    /**
     * Hashes a request so that a recycled key is refused rather than answered with somebody else's
     * resource.
     *
     * <p>Built over the application's own {@code ObjectMapper} when there is one, so that a body its
     * controllers can parse is a body this can canonicalise - a module with its own mapper configuration
     * would disagree with the application about, say, whether a duplicate JSON field is an error, and the
     * two would then compute different fingerprints for the same bytes.
     *
     * @param objectMapper the application's mapper, if it has one
     * @return the fingerprinter
     */
    @Bean
    @ConditionalOnMissingBean
    public RequestFingerprint requestFingerprint(ObjectProvider<ObjectMapper> objectMapper) {
        return new RequestFingerprint(objectMapper.getIfAvailable(ObjectMapper::new));
    }

    /**
     * The no-op metrics binding, for a consumer with no Micrometer.
     *
     * <p>Registered as a real bean rather than left as a null: the store and the filter take it
     * unconditionally, and a null check on a per-request path is a branch that exists only to describe
     * the absence of a dependency.
     *
     * @return the no-op
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotencyMetrics idempotencyMetrics() {
        return IdempotencyMetrics.NOOP;
    }

    /** The clock used when the application has not published one. */
    static Clock clockOf(ObjectProvider<Clock> clock) {
        return clock.getIfAvailable(Clock::systemUTC);
    }

    /**
     * This module's error text, in the languages it ships.
     *
     * <p>Only registered when {@code web-core} is on the classpath. The application's own bundle is always
     * consulted first, so overriding any of these messages means defining the same key locally - no fork
     * and no configuration.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ProblemMessageBundle.class)
    static class ProblemMessagesConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "idempotencyProblemMessageBundle")
        public ProblemMessageBundle idempotencyProblemMessageBundle() {
            return ProblemMessageBundle.of("i18n/ludwig-idempotency-messages");
        }
    }

    /**
     * The Micrometer binding, when there is a registry.
     *
     * <p>A nested class guarded by {@code @ConditionalOnClass} so that a service without Micrometer never
     * loads a type it does not have, and conditional on the bean as well because the registry's own
     * autoconfiguration can be switched off.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(IdempotencyMetrics.class)
        public IdempotencyMetrics micrometerIdempotencyMetrics(ObjectProvider<MeterRegistry> registry) {
            MeterRegistry found = registry.getIfAvailable();
            return found == null ? IdempotencyMetrics.NOOP : new MicrometerIdempotencyMetrics(found);
        }
    }
}
