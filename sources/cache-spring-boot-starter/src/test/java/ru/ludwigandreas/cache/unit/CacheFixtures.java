package ru.ludwigandreas.cache.unit;

import java.time.Duration;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.CachePurpose;
import ru.ludwigandreas.cache.api.CacheTier;
import ru.ludwigandreas.cache.config.CacheProperties;

/**
 * The declarations and configuration the tests in this package share.
 *
 * <p>Two named caches, one of each purpose, because almost every rule in this module applies to exactly one of
 * the two: the TTL ceiling, the refusal of stale reads and the refusal of the load lease are all
 * security-purpose behaviour, and the tests would not say anything if both fixtures were the same kind.
 */
final class CacheFixtures {

    static final String GRANTS = "grants";

    static final String PROFILES = "profiles";

    private CacheFixtures() {
    }

    /** A security-purpose cache: its TTL is a revocation window. */
    static CacheDefinition<TestKey, TestValue> grants() {
        return CacheDefinition.<TestKey, TestValue>named(GRANTS, CachePurpose.SECURITY)
                .owner("cache-spring-boot-starter-tests")
                .defaultTtl(Duration.ofSeconds(30))
                .valueType(TestValue.class)
                .keyRenderer(TestKey::id)
                .build();
    }

    /** A performance-purpose cache: its TTL is a throughput knob. */
    static CacheDefinition<TestKey, TestValue> profiles() {
        return CacheDefinition.<TestKey, TestValue>named(PROFILES, CachePurpose.PERFORMANCE)
                .defaultTtl(Duration.ofMinutes(5))
                .valueType(TestValue.class)
                .keyRenderer(TestKey::id)
                .build();
    }

    /** Properties with one cache block, ready to be adjusted by the caller. */
    static CacheProperties propertiesFor(String cache) {
        CacheProperties properties = new CacheProperties();
        properties.getCaches().put(cache, new CacheProperties.CacheConfig());
        return properties;
    }

    /** Properties whose named cache uses the shared tier, with the top-level shared switch on. */
    static CacheProperties sharedPropertiesFor(String cache) {
        CacheProperties properties = propertiesFor(cache);
        properties.getTiers().getShared().setEnabled(true);
        properties.getCaches().get(cache).getTiers().add(CacheTier.SHARED);
        return properties;
    }
}
