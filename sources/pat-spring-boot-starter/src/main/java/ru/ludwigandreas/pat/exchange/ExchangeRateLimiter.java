package ru.ludwigandreas.pat.exchange;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import ru.ludwigandreas.pat.config.PatProperties;

/**
 * Bounds how fast a credential guess can be tested, per key id and per source.
 *
 * <h2>Why two limits and not one</h2>
 *
 * <p>A per-key limit alone does not bound an attacker at all: each guess names a different key id, so each
 * gets its own fresh budget. A per-source limit alone does not protect a single token from a distributed
 * attempt. Both together mean an attacker needs many sources <em>and</em> is bounded on each.
 *
 * <p>Only <b>failures</b> count. A busy CI pipeline exchanging the same token legitimately a thousand times
 * an hour must not be throttled, and a limit that counted successes would make the limiter a capacity cap
 * on the hot path rather than a brute-force control.
 *
 * <h2>Why this is in-process, and what that means</h2>
 *
 * <p>Deliberately not backed by the shared cache tier. A cross-replica counter would be strictly tighter and
 * would put a Redis round trip on the path of every failed exchange - which is the path an attacker
 * controls the volume of, so it would hand them a way to load the shared tier.
 *
 * <p>The honest consequence: with N replicas an attacker gets N times the configured budget. That is
 * accepted, and it is accepted because the limiter is not what makes the secret unguessable - 256 bits of
 * {@code SecureRandom} is. The limiter exists so that a flood is bounded and <em>visible</em>, and
 * {@code PatMetrics.recordRateLimited} is what makes the second half true. A deployment wanting a strict
 * global limit puts it at the edge, which is where rate limiting belongs anyway and where it can be applied
 * before the request reaches a JVM at all.
 */
public class ExchangeRateLimiter {

    private final PatProperties.RateLimit config;

    private final Clock clock;

    private final Map<String, Window> perKey = new ConcurrentHashMap<>();

    private final Map<String, Window> perSource = new ConcurrentHashMap<>();

    public ExchangeRateLimiter(PatProperties.RateLimit config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    /**
     * Whether this attempt may proceed.
     *
     * <p>Checked before any database access, so a refused key id costs one map lookup. That ordering is the
     * point of having the limiter at all: without it a flood of guesses for one key id is a flood of point
     * reads.
     */
    public boolean allows(String keyId, String sourceIp) {
        if (!config.isEnabled()) {
            return true;
        }
        Instant now = clock.instant();
        return within(perKey, keyId, config.getPerKeyFailures(), now)
                && within(perSource, sourceIp, config.getPerSourceFailures(), now);
    }

    /** Records a failure against both buckets. Only failures are counted. */
    public void recordFailure(String keyId, String sourceIp) {
        if (!config.isEnabled()) {
            return;
        }
        Instant now = clock.instant();
        bump(perKey, keyId, now);
        bump(perSource, sourceIp, now);
    }

    /**
     * Forgets a key's failures after a success.
     *
     * <p>So that a user who mistyped a token several times and then pasted it correctly is not left
     * throttled. Without this, the limiter would punish the most common innocent cause of failures - which
     * is how a security control acquires a reputation for being the problem.
     */
    public void recordSuccess(String keyId) {
        perKey.remove(keyId);
    }

    private boolean within(Map<String, Window> buckets, String key, int limit, Instant now) {
        if (key == null) {
            return true;
        }
        Window window = buckets.get(key);
        return window == null || window.isExpired(now, config.getWindow()) || window.count.get() < limit;
    }

    private void bump(Map<String, Window> buckets, String key, Instant now) {
        if (key == null) {
            return;
        }
        buckets.compute(key, (ignored, existing) -> {
            if (existing == null || existing.isExpired(now, config.getWindow())) {
                return new Window(now);
            }
            existing.count.incrementAndGet();
            return existing;
        });
        // Bounded sweep, so a distributed attempt cannot grow these maps without limit. Done here rather
        // than on a timer because the only thing that grows them is a failure, so the only moment they need
        // sweeping is after one - and a scheduled sweep would be a thread that exists to do nothing.
        if (buckets.size() > MAX_TRACKED) {
            buckets.entrySet().removeIf(entry -> entry.getValue().isExpired(now, config.getWindow()));
        }
    }

    /**
     * How many distinct keys or sources are tracked before expired entries are swept.
     *
     * <p>A bound rather than a tuning knob: without it, an attacker enumerating key ids would grow this map
     * once per guess, and a rate limiter that can be turned into a memory exhaustion is worse than none.
     */
    private static final int MAX_TRACKED = 100_000;

    /** A fixed window. Simpler than a sliding one, and the imprecision at the boundary is not a weakness. */
    private static final class Window {

        private final Instant startedAt;

        private final AtomicInteger count = new AtomicInteger(1);

        private Window(Instant startedAt) {
            this.startedAt = startedAt;
        }

        private boolean isExpired(Instant now, java.time.Duration length) {
            return startedAt.plus(length).isBefore(now);
        }
    }
}
