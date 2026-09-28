package ru.ludwigandreas.cache.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.cache.api.CacheLoad;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.cache.config.CacheProperties;
import ru.ludwigandreas.cache.test.LudwigCacheTestSupport;

/**
 * How a miss is loaded: once per key however many callers are waiting, with an absence cacheable and a failure
 * never.
 */
class CacheLoadingTest {

    private static final TestKey ALICE = new TestKey("alice");

    @Test
    @DisplayName("concurrent misses for one key run the loader once")
    void concurrentMissesAreCoalesced() throws Exception {
        // The failure this prevents is self-inflicted and arrives at the worst moment: when a hot key
        // expires, every in-flight request for it misses at once and, without coalescing, each issues its
        // own load against the store - a thundering herd timed to peak traffic.
        int threads = 16;
        LudwigCache<TestKey, TestValue> cache = LudwigCacheTestSupport.cache(CacheFixtures.grants());
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        cache.get(ALICE, key -> {
                            loads.incrementAndGet();
                            sleep();
                            return CacheLoad.present(new TestValue("granted", 1));
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(loads).as("one load for %d concurrent callers", threads).hasValue(1);
    }

    @Test
    @DisplayName("different keys do not block each other - the lock is per key, not per cache")
    void differentKeysDoNotContend() {
        LudwigCache<TestKey, TestValue> cache = LudwigCacheTestSupport.cache(CacheFixtures.grants());
        AtomicInteger loads = new AtomicInteger();

        for (String id : List.of("a", "b", "c")) {
            cache.get(new TestKey(id), key -> {
                loads.incrementAndGet();
                return CacheLoad.present(new TestValue(key.id(), 1));
            });
        }

        assertThat(loads).hasValue(3);
    }

    @Test
    @DisplayName("an absence is cached, so a hammering lookup for a key that does not exist stops loading")
    void absenceIsCachedWhenEnabled() {
        CacheProperties properties = CacheFixtures.propertiesFor(CacheFixtures.PROFILES);
        properties.getCaches().get(CacheFixtures.PROFILES).getNegative().setEnabled(true);
        LudwigCache<TestKey, TestValue> cache =
                LudwigCacheTestSupport.cache(properties, CacheFixtures.profiles());
        AtomicInteger loads = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            Optional<TestValue> found = cache.get(ALICE, key -> {
                loads.incrementAndGet();
                return CacheLoad.absent();
            });
            assertThat(found).isEmpty();
        }

        assertThat(loads).as("the absence answered the second and third call").hasValue(1);
    }

    @Test
    @DisplayName("with negative caching off, every call for an absent key loads again")
    void absenceIsNotCachedByDefault() {
        LudwigCache<TestKey, TestValue> cache = LudwigCacheTestSupport.cache(CacheFixtures.profiles());
        AtomicInteger loads = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            assertThat(cache.get(ALICE, key -> {
                loads.incrementAndGet();
                return CacheLoad.absent();
            })).isEmpty();
        }

        assertThat(loads).hasValue(3);
    }

    /**
     * The distinction the whole {@link CacheLoad} type exists for.
     *
     * <p>Caching a failure turns a two-second blip into a sustained outage for exactly the keys that were
     * unlucky, and it is self-reinforcing: the negative entry suppresses the retry that would have noticed the
     * store came back. So a thrown loader must leave nothing behind, even on a cache whose negative caching is
     * switched on - which is the configuration this test uses, because that is where getting it wrong would be
     * invisible.
     */
    @Test
    @DisplayName("a failed load is never cached, even with negative caching enabled, and the next call retries")
    void failureIsNotCached() {
        CacheProperties properties = CacheFixtures.propertiesFor(CacheFixtures.PROFILES);
        properties.getCaches().get(CacheFixtures.PROFILES).getNegative().setEnabled(true);
        properties.getCaches().get(CacheFixtures.PROFILES).getNegative().setTtl(Duration.ofSeconds(30));
        LudwigCache<TestKey, TestValue> cache =
                LudwigCacheTestSupport.cache(properties, CacheFixtures.profiles());
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> cache.get(ALICE, key -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("the store is down");
        })).isInstanceOf(IllegalStateException.class).hasMessage("the store is down");

        Optional<TestValue> recovered = cache.get(ALICE, key -> {
            attempts.incrementAndGet();
            return CacheLoad.present(new TestValue("recovered", 1));
        });

        assertThat(attempts).as("the failure did not become a cached absence").hasValue(2);
        assertThat(recovered).contains(new TestValue("recovered", 1));
    }

    @Test
    @DisplayName("a loader returning CacheLoad.failed behaves exactly as one that throws")
    void returnedFailureBehavesAsAThrownOne() {
        LudwigCache<TestKey, TestValue> cache = LudwigCacheTestSupport.cache(CacheFixtures.profiles());

        assertThatThrownBy(() -> cache.get(ALICE,
                key -> CacheLoad.failed(new IllegalStateException("timeout"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cache.get(ALICE)).isEmpty();
    }

    @Test
    @DisplayName("a value loaded once is served from the cache, and put makes it visible immediately")
    void valuesAreHeld() {
        LudwigCache<TestKey, TestValue> cache = LudwigCacheTestSupport.cache(CacheFixtures.grants());

        cache.put(ALICE, new TestValue("granted", 1));

        assertThat(cache.get(ALICE)).contains(new TestValue("granted", 1));
        assertThat(cache.estimatedSize()).isEqualTo(1);
    }

    private static void sleep() {
        try {
            // Long enough that every other thread is certainly inside get() by now, which is what makes
            // this a race rather than a sequence.
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
