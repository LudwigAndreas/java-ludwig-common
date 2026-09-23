package ru.ludwigandreas.usersettings.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.cache.CaffeineSettingsCache;
import ru.ludwigandreas.usersettings.cache.NoopSettingsCache;
import ru.ludwigandreas.usersettings.cache.SettingsCache;

/**
 * The cache's two load-bearing properties: it coalesces concurrent misses, and it does not mix
 * tenants.
 */
class SettingsCacheTest {

    private static final SettingsSubject ACME =
            new SettingsSubject(PrincipalRef.user("user-1"), "acme");
    private static final SettingsSubject GLOBEX =
            new SettingsSubject(PrincipalRef.user("user-1"), "globex");

    private static ResolvedSettings empty(SettingsSubject subject) {
        return new ResolvedSettings(subject, Map.of());
    }

    private static CaffeineSettingsCache cache() {
        return new CaffeineSettingsCache(Duration.ofMinutes(5), 1_000);
    }

    @Test
    @DisplayName("concurrent misses for one subject run the loader once")
    void concurrent_misses_are_coalesced() throws Exception {
        // The thundering herd this cache exists to prevent: without coalescing, every expiry of a hot
        // subject releases one resolution per in-flight request at once, against the database,
        // exactly when traffic is highest.
        int threads = 16;
        CaffeineSettingsCache cache = cache();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        cache.get(ACME, subject -> {
                            loads.incrementAndGet();
                            // Long enough that every other thread is certainly inside get() by now,
                            // which is what makes this a race rather than a sequence.
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return empty(subject);
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

        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same subject in two tenants is two entries")
    void tenants_do_not_share_an_entry() {
        // Keying on the principal alone would serve one tenant's answer to the other's administrator.
        CaffeineSettingsCache cache = cache();
        AtomicInteger loads = new AtomicInteger();

        cache.get(ACME, subject -> {
            loads.incrementAndGet();
            return empty(subject);
        });
        ResolvedSettings other = cache.get(GLOBEX, subject -> {
            loads.incrementAndGet();
            return empty(subject);
        });

        assertThat(loads.get()).isEqualTo(2);
        assertThat(other.subject().tenantId()).isEqualTo("globex");
    }

    @Test
    @DisplayName("evicting one subject-and-tenant leaves the other tenant cached")
    void evicting_one_entry_leaves_the_other() {
        CaffeineSettingsCache cache = cache();
        cache.put(ACME, empty(ACME));
        cache.put(GLOBEX, empty(GLOBEX));

        cache.evict(ACME);

        assertThat(cache.get(ACME)).isEmpty();
        assertThat(cache.get(GLOBEX)).isPresent();
    }

    @Test
    @DisplayName("evicting by principal clears every tenant that subject is cached under")
    void evicting_by_principal_clears_every_tenant() {
        CaffeineSettingsCache cache = cache();
        cache.put(ACME, empty(ACME));
        cache.put(GLOBEX, empty(GLOBEX));

        cache.evict(PrincipalRef.user("user-1"));

        assertThat(cache.get(ACME)).isEmpty();
        assertThat(cache.get(GLOBEX)).isEmpty();
    }

    @Test
    @DisplayName("evictAll clears everything, which is what a tenant-layer write needs")
    void evict_all_clears_everything() {
        CaffeineSettingsCache cache = cache();
        cache.put(ACME, empty(ACME));
        cache.put(GLOBEX, empty(GLOBEX));

        cache.evictAll();

        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("the no-op cache always misses and still serves the loader")
    void noop_cache_always_misses() {
        SettingsCache cache = new NoopSettingsCache();
        AtomicInteger loads = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            cache.get(ACME, subject -> {
                loads.incrementAndGet();
                return empty(subject);
            });
        }

        assertThat(loads.get()).isEqualTo(3);
        assertThat(cache.get(ACME)).isEmpty();
        // Every eviction path has to be callable without a cache behind it, because the write path
        // calls them unconditionally.
        List.<Runnable>of(() -> cache.evict(ACME), () -> cache.evict(PrincipalRef.user("user-1")),
                cache::evictAll).forEach(Runnable::run);
    }
}
