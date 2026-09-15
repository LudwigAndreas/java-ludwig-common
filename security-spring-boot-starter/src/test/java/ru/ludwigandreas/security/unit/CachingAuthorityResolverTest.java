package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityResolver;
import ru.ludwigandreas.security.authz.CachingAuthorityResolver;
import ru.ludwigandreas.security.authz.CaffeineAuthorityCache;
import ru.ludwigandreas.security.authz.NoopAuthorityCache;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.metrics.SecurityMetrics;
import ru.ludwigandreas.security.principal.PrincipalType;

@ExtendWith(MockitoExtension.class)
class CachingAuthorityResolverTest {

    private static final PrincipalRef ALICE = PrincipalRef.user("alice");

    @Mock
    private SecurityMetrics metrics;

    private CachingAuthorityResolver caching(AuthorityResolver delegate,
                                             ru.ludwigandreas.security.authz.AuthorityCache cache) {
        return new CachingAuthorityResolver(delegate, cache, metrics);
    }

    @Test
    void recordsAMissThenHitsForTheSamePrincipal() {
        AtomicInteger calls = new AtomicInteger();
        CachingAuthorityResolver resolver = caching(ref -> {
            calls.incrementAndGet();
            return Authorities.builder().roles(Set.of("AGENT")).build();
        }, new CaffeineAuthorityCache(Duration.ofMinutes(1), 100));

        assertThat(resolver.lookup(ALICE).roles()).containsExactly("ROLE_AGENT");
        assertThat(resolver.lookup(ALICE).roles()).containsExactly("ROLE_AGENT");

        assertThat(calls).hasValue(1);
        verify(metrics).recordAuthorityCacheMiss(PrincipalType.USER.name());
        verify(metrics).recordAuthorityCacheHit(PrincipalType.USER.name());
    }

    /**
     * The failure this guards against is self-inflicted and arrives at the worst moment: when a hot
     * subject's entry expires, every in-flight request for them misses at once and, without
     * coalescing, each issues its own query against the role store.
     */
    @Test
    @DisplayName("concurrent misses for one principal collapse into a single load")
    void coalescesConcurrentMisses() throws Exception {
        int threads = 16;
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        CachingAuthorityResolver resolver = caching(ref -> {
            loads.incrementAndGet();
            sleep();
            return Authorities.builder().roles(Set.of("AGENT")).build();
        }, new CaffeineAuthorityCache(Duration.ofMinutes(1), 100));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<Authorities>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return resolver.lookup(ALICE);
                    } finally {
                        done.countDown();
                    }
                }));
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

            for (var result : results) {
                assertThat(result.get().roles()).containsExactly("ROLE_AGENT");
            }
            assertThat(loads).as("one load for %d concurrent callers", threads).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("without a cache every call re-resolves - correct, just uncached")
    void noopCacheAlwaysDelegates() {
        AtomicInteger calls = new AtomicInteger();
        CachingAuthorityResolver resolver = caching(ref -> {
            calls.incrementAndGet();
            return Authorities.none();
        }, new NoopAuthorityCache());

        resolver.lookup(ALICE);
        resolver.lookup(ALICE);

        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("a resolver returning null is a bug, and must not become a null principal downstream")
    void treatsANullResultAsNoGrant() {
        CachingAuthorityResolver resolver = caching(ref -> null, new NoopAuthorityCache());

        assertThat(resolver.lookup(ALICE).isEmpty()).isTrue();
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
