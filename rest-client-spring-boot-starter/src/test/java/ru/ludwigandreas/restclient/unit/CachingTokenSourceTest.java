package ru.ludwigandreas.restclient.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.restclient.auth.CachedToken;
import ru.ludwigandreas.restclient.auth.CachingTokenSource;

/**
 * Token caching, early refresh, and the single-flight property - the one that keeps a service from
 * sending two hundred simultaneous requests to its authorization server every token lifetime.
 */
class CachingTokenSourceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final Duration SKEW = Duration.ofSeconds(30);

    @Test
    @DisplayName("a valid cached token is reused rather than re-minted")
    void reusesAValidToken() {
        AtomicInteger mints = new AtomicInteger();
        CachingTokenSource tokens = source(mints, Duration.ofMinutes(10));

        tokens.get(false);
        tokens.get(false);

        assertThat(mints.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a token inside the refresh skew is treated as already expired")
    void refreshesInsideTheSkew() {
        AtomicInteger mints = new AtomicInteger();
        // Expires in 10s, skew is 30s: it would not survive the call it is about to be used for.
        CachingTokenSource tokens = source(mints, Duration.ofSeconds(10));

        tokens.get(false);
        tokens.get(false);

        assertThat(mints.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a forced refresh discards the cached token")
    void forcedRefreshMints() {
        AtomicInteger mints = new AtomicInteger();
        CachingTokenSource tokens = source(mints, Duration.ofMinutes(10));

        tokens.get(false);
        tokens.get(true);

        assertThat(mints.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("invalidate() makes the next call mint")
    void invalidateForcesTheNextMint() {
        AtomicInteger mints = new AtomicInteger();
        CachingTokenSource tokens = source(mints, Duration.ofMinutes(10));

        tokens.get(false);
        tokens.invalidate();
        tokens.get(false);

        assertThat(mints.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("fifty threads arriving at an empty cache mint exactly one token")
    void mintsOnceUnderConcurrency() throws Exception {
        AtomicInteger mints = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(50);
        CachingTokenSource tokens = new CachingTokenSource("billing",
                () -> {
                    mints.incrementAndGet();
                    // A real token endpoint takes milliseconds, which is the window in which a naive
                    // cache lets every waiting caller start its own request.
                    sleep();
                    return new CachedToken("token", NOW.plus(Duration.ofMinutes(10)));
                },
                SKEW, Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC), () -> { });

        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            for (int i = 0; i < 50; i++) {
                pool.submit(() -> {
                    await(start);
                    tokens.get(false);
                    done.countDown();
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(mints.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("the async path completes immediately from the cache without touching the executor")
    void asyncPathServesFromCacheWithoutTheExecutor() throws Exception {
        AtomicInteger mints = new AtomicInteger();
        CachingTokenSource tokens = source(mints, Duration.ofMinutes(10));
        tokens.get(false);

        CachedToken token = tokens.getAsync(false).get(1, TimeUnit.SECONDS);

        assertThat(token.value()).isEqualTo("token");
        assertThat(mints.get()).isEqualTo(1);
    }

    private CachingTokenSource source(AtomicInteger mints, Duration lifetime) {
        return new CachingTokenSource("billing",
                () -> {
                    mints.incrementAndGet();
                    return new CachedToken("token", NOW.plus(lifetime));
                },
                SKEW, Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC), () -> { });
    }

    private void sleep() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
