package ru.ludwigandreas.cache.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.LudwigCacheRegistry;
import ru.ludwigandreas.cache.core.CacheRefreshExecutor;
import ru.ludwigandreas.cache.metrics.CacheMetrics;
import ru.ludwigandreas.cache.metrics.MicrometerCacheMetrics;
import ru.ludwigandreas.cache.metrics.NoopCacheMetrics;
import ru.ludwigandreas.cache.shared.SharedCacheTier;

/**
 * The module's beans: the registry, the merge, the validator, the refresh pool and the metrics.
 *
 * <p>Gated on {@code ludwig.cache.enabled}, which defaults to on. With it off nothing here is registered,
 * and a module asking for a cache gets a context startup failure rather than an unconfigured cache - which
 * is the right outcome, because a service that has turned caching off and still has modules requiring it
 * has a dependency problem rather than a caching problem. To run without caches but with the modules that
 * use them, leave this on and set {@code enabled: false} on each cache.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = CacheProperties.PREFIX, name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(CacheProperties.class)
public class LudwigCacheAutoConfiguration {

    /**
     * Micrometer when a registry is present, and no-ops otherwise.
     *
     * <p>Caffeine's own statistics are collected either way: {@code recordStats()} is unconditional, so a
     * service that adds a registry later sees the hit ratio from that point without the change of behaviour
     * a conditional would have introduced.
     */
    @Bean
    @ConditionalOnMissingBean(CacheMetrics.class)
    public CacheMetrics ludwigCacheMetrics(ObjectProvider<MeterRegistry> registry) {
        MeterRegistry found = registry.getIfAvailable();
        return found == null ? new NoopCacheMetrics() : new MicrometerCacheMetrics(found);
    }

    @Bean
    @ConditionalOnMissingBean(CacheSettingsResolver.class)
    public CacheSettingsResolver ludwigCacheSettingsResolver(CacheProperties properties) {
        return new CacheSettingsResolver(properties);
    }

    /**
     * The startup validator.
     *
     * <p>It is a bean rather than a private step of the registry so that a test can exercise one unsafe
     * combination without a Spring context, and so that the message it produces is somebody's unit test.
     */
    @Bean
    @ConditionalOnMissingBean(CacheConfigurationValidator.class)
    public CacheConfigurationValidator ludwigCacheConfigurationValidator(CacheProperties properties,
                                                                         CacheSettingsResolver resolver,
                                                                         Environment environment) {
        return new CacheConfigurationValidator(properties, resolver,
                environment.getProperty("spring.application.name"));
    }

    /** The bounded, discarding pool early refreshes run on. See {@link CacheRefreshExecutor}. */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(CacheRefreshExecutor.class)
    public CacheRefreshExecutor ludwigCacheRefreshExecutor(CacheProperties properties) {
        return new CacheRefreshExecutor(properties.getRefresh().getPoolSize(),
                properties.getRefresh().getQueueCapacity());
    }

    /**
     * The registry every module resolves its cache through.
     *
     * <p>{@code List<CacheDefinition<?, ?>>} collects every declaration on the classpath. Definitions are
     * plain data beans with no dependency on this registry, so Spring creates all of them first - which is
     * what lets the constructor validate the complete set before any cache is built from it.
     *
     * <p>The {@link Clock} comes from the context if a service publishes one and defaults to UTC otherwise.
     * No {@code Clock} bean is declared here: several modules in this platform want one, and a starter that
     * contributed its own would decide the question for all of them.
     */
    @Bean
    @ConditionalOnMissingBean(LudwigCacheRegistry.class)
    public LudwigCacheRegistry ludwigCacheRegistry(List<CacheDefinition<?, ?>> definitions,
                                                   CacheSettingsResolver resolver,
                                                   CacheConfigurationValidator validator,
                                                   ObjectProvider<SharedCacheTier> sharedTier,
                                                   CacheMetrics metrics,
                                                   ObjectProvider<Clock> clock,
                                                   CacheRefreshExecutor refreshExecutor) {
        return new DefaultLudwigCacheRegistry(definitions, resolver, validator,
                sharedTier.getIfAvailable(), metrics, clock.getIfAvailable(Clock::systemUTC),
                refreshExecutor);
    }
}
