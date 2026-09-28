package ru.ludwigandreas.cache.unit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.cache.api.LudwigCacheRegistry;
import ru.ludwigandreas.cache.config.LudwigCacheAutoConfiguration;
import ru.ludwigandreas.cache.core.CacheRefreshExecutor;
import ru.ludwigandreas.cache.metrics.CacheMetrics;
import ru.ludwigandreas.cache.metrics.MicrometerCacheMetrics;
import ru.ludwigandreas.cache.metrics.NoopCacheMetrics;
import ru.ludwigandreas.cache.shared.SharedCacheTier;

/** What the module wires up, and what it refuses to start with. */
class CacheAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LudwigCacheAutoConfiguration.class))
            .withUserConfiguration(DeclaringModule.class);

    @Test
    @DisplayName("a service that configures nothing gets a working registry and its declared caches")
    void registersDefaults() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(LudwigCacheRegistry.class)
                    .hasSingleBean(CacheRefreshExecutor.class)
                    .hasSingleBean(CacheMetrics.class);
            assertThat(context.getBean(LudwigCacheRegistry.class).names())
                    .containsExactly(CacheFixtures.PROFILES);
        });
    }

    @Test
    @DisplayName("without a MeterRegistry the metrics are no-ops rather than a startup failure")
    void metricsDegradeWithoutARegistry() {
        runner.run(context -> assertThat(context.getBean(CacheMetrics.class))
                .isInstanceOf(NoopCacheMetrics.class));
    }

    @Test
    @DisplayName("with a MeterRegistry the caches are bound to Micrometer")
    void metricsUseMicrometerWhenAvailable() {
        runner.withBean(SimpleMeterRegistry.class)
                .run(context -> assertThat(context.getBean(CacheMetrics.class))
                        .isInstanceOf(MicrometerCacheMetrics.class));
    }

    /**
     * The typo check, exercised through a context rather than only against the validator.
     *
     * <p>Worth doing both ways: the validator's own test proves the message, and this proves that the check
     * actually runs during startup - which depends on it being called from the registry's constructor rather
     * than from a {@code @PostConstruct} that could run after a module was already handed a cache.
     */
    @Test
    @DisplayName("a ludwig.cache.caches block that no module declares fails the context")
    void anUndeclaredCacheBlockFailsTheContext() {
        runner.withPropertyValues("ludwig.cache.caches.profilez.ttl=1m")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessageContaining("profilez"));
    }

    @Test
    @DisplayName("the master switch off registers nothing at all")
    void masterSwitchRegistersNothing() {
        runner.withPropertyValues("ludwig.cache.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(LudwigCacheRegistry.class));
    }

    /**
     * No Redis on the context means no shared tier bean, and that is not an error on its own.
     *
     * <p>A cache that <em>lists</em> the shared tier in that state fails at startup from the registry, with a
     * message naming the missing piece. Running local-only instead would be worse than failing: the reason
     * somebody listed the shared tier is that they expected the other replicas to see their entries.
     */
    @Test
    @DisplayName("no shared tier is registered when Redis is not configured")
    void noSharedTierWithoutRedis() {
        runner.run(context -> assertThat(context).doesNotHaveBean(SharedCacheTier.class));
    }

    @Test
    @DisplayName("resolving the same declaration twice returns the same cache, not two copies of the entries")
    void cachesAreMemoized() {
        runner.run(context -> {
            LudwigCacheRegistry registry = context.getBean(LudwigCacheRegistry.class);
            LudwigCache<TestKey, TestValue> first = registry.cache(CacheFixtures.profiles());
            LudwigCache<TestKey, TestValue> second = registry.cache(CacheFixtures.profiles());
            assertThat(first).isSameAs(second);
        });
    }

    /** A module declaring one cache, exactly as a real starter does. */
    @Configuration(proxyBeanMethods = false)
    static class DeclaringModule {

        @Bean
        CacheDefinition<TestKey, TestValue> profilesCacheDefinition() {
            return CacheFixtures.profiles();
        }
    }
}
