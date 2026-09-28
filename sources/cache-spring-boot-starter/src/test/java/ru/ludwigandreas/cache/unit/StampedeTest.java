package ru.ludwigandreas.cache.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.cache.api.CacheLoad;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.cache.config.CacheProperties;
import ru.ludwigandreas.cache.test.LudwigCacheTestSupport;

/**
 * The part per-key coalescing cannot solve.
 *
 * <p>Caffeine computes at most once <em>per process</em>, so N replicas still issue N loads the moment a hot key
 * expires - and they do it simultaneously, because they all cached it during the same deploy or the same cold
 * start. The herd is caused by synchronised expiry, not by concurrency, and these are the two answers to it.
 */
class StampedeTest {

    private static final TestKey ALICE = new TestKey("alice");

    private static final Duration TTL = Duration.ofSeconds(30);

    /**
     * Refreshing before expiry, on a probability that rises as the entry ages.
     *
     * <p>The test sits at the far end of the window, where the probability reaches one, so the behaviour is
     * deterministic rather than flaky. Everywhere earlier in the window it is a coin weighted by age, which is
     * the point: each replica rolls independently, so the reloads spread across the tail of the TTL instead of
     * landing together on its edge.
     */
    @Test
    @DisplayName("a read at the end of the TTL refreshes the entry and still serves the current value")
    void earlyRefreshReloadsWithoutMakingTheCallerWait() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        RecordingCacheMetrics metrics = new RecordingCacheMetrics();
        LudwigCache<TestKey, TestValue> cache = cache(performanceProperties(), clock, metrics, null);
        cache.put(ALICE, new TestValue("first", 1));

        clock.advance(TTL);
        AtomicInteger loads = new AtomicInteger();
        TestValue served = cache.get(ALICE, key -> {
            loads.incrementAndGet();
            return CacheLoad.present(new TestValue("second", 2));
        }).orElseThrow();

        assertThat(served).as("the caller is served the current value, not made to wait for the refresh")
                .isEqualTo(new TestValue("first", 1));
        assertThat(loads).as("the refresh ran").hasValue(1);
        assertThat(metrics.earlyRefreshes()).isEqualTo(1);
        assertThat(cache.get(ALICE)).contains(new TestValue("second", 2));
    }

    @Test
    @DisplayName("early in the TTL nothing is refreshed - the window starts at the configured threshold")
    void noRefreshBeforeTheThreshold() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        RecordingCacheMetrics metrics = new RecordingCacheMetrics();
        LudwigCache<TestKey, TestValue> cache = cache(performanceProperties(), clock, metrics, null);
        cache.put(ALICE, new TestValue("first", 1));

        clock.advance(Duration.ofSeconds(1));
        cache.get(ALICE, key -> CacheLoad.present(new TestValue("second", 2)));

        assertThat(metrics.earlyRefreshes()).isZero();
        assertThat(cache.get(ALICE)).contains(new TestValue("first", 1));
    }

    /**
     * The replica that loses the cluster-wide lease serves the stale value rather than blocking.
     *
     * <p>Blocking on a lease whose holder may have crashed converts a slow load into a stalled request, which is
     * worse than the mild staleness the lease is trading for. A loser with nothing stale to serve loads anyway,
     * for the same reason.
     */
    @Test
    @DisplayName("a lease loser with a stale entry serves it instead of loading")
    void leaseLoserServesStale() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        RecordingCacheMetrics metrics = new RecordingCacheMetrics();
        FakeSharedCacheTier tier = new FakeSharedCacheTier();
        tier.grantLeases(false);

        CacheProperties properties = CacheFixtures.sharedPropertiesFor(CacheFixtures.PROFILES);
        CacheProperties.CacheConfig config = properties.getCaches().get(CacheFixtures.PROFILES);
        config.setTtl(TTL);
        config.getStampede().getEarlyRefresh().setEnabled(false);
        config.getStampede().setStaleGrace(Duration.ofSeconds(10));
        config.getStampede().getLease().setEnabled(true);
        config.getStampede().getLease().setTtl(Duration.ofSeconds(5));

        LudwigCache<TestKey, TestValue> cache = cache(properties, clock, metrics, tier);
        cache.put(ALICE, new TestValue("first", 1));
        clock.advance(TTL.plusSeconds(1));

        AtomicInteger loads = new AtomicInteger();
        TestValue served = cache.get(ALICE, key -> {
            loads.incrementAndGet();
            return CacheLoad.present(new TestValue("second", 2));
        }).orElseThrow();

        assertThat(served).isEqualTo(new TestValue("first", 1));
        assertThat(loads).as("the lease holder loads; this replica does not").hasValue(0);
        assertThat(metrics.events()).contains("lease-lost:profiles:stale");
    }

    @Test
    @DisplayName("a lease loser past the stale grace loads anyway rather than stalling")
    void leaseLoserWithNothingStaleLoadsAnyway() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        RecordingCacheMetrics metrics = new RecordingCacheMetrics();
        FakeSharedCacheTier tier = new FakeSharedCacheTier();
        tier.grantLeases(false);

        CacheProperties properties = CacheFixtures.sharedPropertiesFor(CacheFixtures.PROFILES);
        CacheProperties.CacheConfig config = properties.getCaches().get(CacheFixtures.PROFILES);
        config.setTtl(TTL);
        config.getStampede().getEarlyRefresh().setEnabled(false);
        config.getStampede().setStaleGrace(Duration.ofSeconds(10));
        config.getStampede().getLease().setEnabled(true);
        config.getStampede().getLease().setTtl(Duration.ofSeconds(5));

        LudwigCache<TestKey, TestValue> cache = cache(properties, clock, metrics, tier);
        AtomicInteger loads = new AtomicInteger();

        cache.get(ALICE, key -> {
            loads.incrementAndGet();
            return CacheLoad.present(new TestValue("second", 2));
        });

        assertThat(loads).hasValue(1);
        assertThat(metrics.events()).contains("lease-lost:profiles:loaded");
    }

    private static CacheProperties performanceProperties() {
        CacheProperties properties = CacheFixtures.propertiesFor(CacheFixtures.PROFILES);
        properties.getCaches().get(CacheFixtures.PROFILES).setTtl(TTL);
        return properties;
    }

    private static LudwigCache<TestKey, TestValue> cache(CacheProperties properties,
                                                         MutableClock clock,
                                                         RecordingCacheMetrics metrics,
                                                         FakeSharedCacheTier tier) {
        return LudwigCacheTestSupport
                .registry(properties, clock, metrics, tier, CacheFixtures.profiles())
                .cache(CacheFixtures.profiles());
    }
}
