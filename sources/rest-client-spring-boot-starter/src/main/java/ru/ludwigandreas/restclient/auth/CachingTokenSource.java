package ru.ludwigandreas.restclient.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caches one client's token and mints a new one exactly once, however many callers need it.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>The naive cache - check, and if stale, mint - behaves fine under test and badly in production.
 * At the instant a token expires, every in-flight request discovers it is stale simultaneously, and
 * a service handling 200 concurrent calls sends 200 token requests to the authorization server, at
 * the same millisecond, every time the token rolls over. Authorization servers rate-limit; the ones
 * that do not fall over. The symptom is a burst of 429s or 503s from the IdP once per token
 * lifetime, which looks like an IdP problem and is not.
 *
 * <p>Single-flight fixes it: the first caller mints, everyone else waits for that one result. On the
 * blocking path this is a lock and a double check; on the reactive path it is one shared
 * {@link CompletableFuture} that later callers attach to.
 *
 * <h2>Forced refresh</h2>
 *
 * <p>A 401 means the cached token is dead earlier than its {@code exp} claimed - a revocation, a
 * rotated signing key, a clock that disagrees. {@code force} discards it. The generation counter is
 * what keeps a forced refresh from becoming a stampede of its own: if another thread already minted
 * a token <em>after</em> this caller read the stale one, that new token is used instead of minting a
 * third.
 */
public class CachingTokenSource {

    private static final Logger log = LoggerFactory.getLogger(CachingTokenSource.class);

    private final String clientName;
    private final Supplier<CachedToken> minter;
    private final Duration skew;
    private final Executor refreshExecutor;
    private final Clock clock;
    private final Runnable onRefresh;

    private final Object lock = new Object();

    private volatile CachedToken current;
    private volatile long generation;
    private CompletableFuture<CachedToken> inFlight;

    /** Creates the token source for one named client. */
    // CHECKSTYLE.OFF: ParameterNumber - six collaborators, all of them genuinely per-client state.
    public CachingTokenSource(String clientName, Supplier<CachedToken> minter, Duration skew,
                              Executor refreshExecutor, Clock clock, Runnable onRefresh) {
        this.clientName = clientName;
        this.minter = minter;
        this.skew = skew;
        this.refreshExecutor = refreshExecutor;
        this.clock = clock;
        this.onRefresh = onRefresh;
    }
    // CHECKSTYLE.ON: ParameterNumber

    /** The current token, minting one on the calling thread if needed. */
    public CachedToken get(boolean force) {
        CachedToken cached = current;
        long seenGeneration = generation;
        if (!force && usable(cached)) {
            return cached;
        }
        synchronized (lock) {
            // Re-read inside the lock. Between the check above and here, another caller may have
            // minted - and for a forced refresh that is the common case, because a 401 usually hits
            // several in-flight requests at once.
            if (generation != seenGeneration && usable(current)) {
                return current;
            }
            if (!force && usable(current)) {
                return current;
            }
            return mintLocked();
        }
    }

    /**
     * The current token without blocking the calling thread.
     *
     * <p>A valid cached token completes immediately - which is the overwhelming majority of calls, so
     * the reactive path normally costs nothing. Only an actual mint is handed to the executor.
     */
    public CompletableFuture<CachedToken> getAsync(boolean force) {
        CachedToken cached = current;
        if (!force && usable(cached)) {
            return CompletableFuture.completedFuture(cached);
        }
        synchronized (lock) {
            if (!force && usable(current)) {
                return CompletableFuture.completedFuture(current);
            }
            if (inFlight != null) {
                // Someone is already minting. Attaching to their future is what makes this
                // single-flight rather than "one request per caller, started concurrently".
                return inFlight;
            }
            CompletableFuture<CachedToken> future = CompletableFuture
                    .supplyAsync(this::mintOnce, refreshExecutor)
                    .whenComplete(this::publish);
            inFlight = future;
            return future;
        }
    }

    /** Discards the cached token, so the next call mints. */
    public void invalidate() {
        synchronized (lock) {
            current = null;
            generation++;
        }
    }

    private CachedToken mintLocked() {
        CachedToken minted = mintOnce();
        current = minted;
        generation++;
        return minted;
    }

    private CachedToken mintOnce() {
        onRefresh.run();
        CachedToken minted = minter.get();
        // Logged without the token, at DEBUG, and only on an actual mint - which is the event worth
        // seeing when someone is working out why the IdP is being called every second.
        log.debug("Client {}: minted a new access token, expires at {}", clientName,
                minted == null ? null : minted.expiresAt());
        return minted;
    }

    private void publish(CachedToken minted, Throwable failure) {
        synchronized (lock) {
            if (failure == null) {
                current = minted;
                generation++;
            }
            inFlight = null;
        }
    }

    private boolean usable(CachedToken token) {
        return token != null && token.usableAt(clock.instant(), skew);
    }
}
