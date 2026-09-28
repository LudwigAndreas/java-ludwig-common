package ru.ludwigandreas.cache.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import ru.ludwigandreas.cache.api.CacheSettings;
import ru.ludwigandreas.cache.metrics.CacheMetrics;

/**
 * The shared tier over Redis.
 *
 * <h2>Read this before adding {@code shared} to a cache's tiers</h2>
 *
 * <p>A shared tier is not a free hit-ratio improvement. It adds a failure mode the single-tier
 * arrangement does not have, and the failure is in the <em>eviction</em> rather than the read:
 *
 * <ul>
 *   <li>evicting a shared entry is a network call that can fail. If it fails, <b>every replica</b> keeps
 *       serving the stale value - strictly worse than local-only, where only the writing replica was
 *       wrong and every other replica's TTL was already independent and already ticking;</li>
 *   <li>deleting the shared entry does nothing about the <em>local</em> copies the other replicas hold.
 *       That is what the invalidation channel is for, and why it is on by default: without it, local TTL
 *       is the only bound on those copies, and a five-minute local TTL means a five-minute stale read
 *       after a successful shared eviction.</li>
 * </ul>
 *
 * <p>So a two-tier eviction is three things, and this class implements all three: drop the local entry on
 * this replica, delete the shared entry with retries and <b>a metric on failure</b>, and announce the key
 * on the invalidation channel so the other replicas drop theirs. Turning the third one off is allowed and
 * is not silent: the startup validator then requires the local TTL to be at or below
 * {@code ludwig.cache.tiers.shared.local-ttl-cap-without-invalidation}, because that TTL has become the
 * only consistency bound in the deployment.
 *
 * <p>This caveat is on the class and not only in the README, following
 * {@code RedisIdempotencyStore}'s example: a team reaching for the shared tier is doing so for throughput,
 * at the moment they are editing configuration, and the README is not what they are reading.
 *
 * <h2>Unavailable is not an error</h2>
 *
 * <p>Every read and write is best-effort. A Redis that cannot be reached makes the service slower, not
 * broken, so an unreachable tier is counted, logged once per cooldown, and bypassed in favour of the local
 * tier. The one operation that reports its failure upward is eviction, for the reason above.
 */
@Slf4j
public class RedisSharedCacheTier implements SharedCacheTier {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RedisMessageListenerContainer listeners;
    private final CacheMetrics metrics;
    private final Clock clock;
    private final String applicationName;

    /**
     * @param redis           the template; {@code StringRedisTemplate} rather than a typed one because the
     *                        envelope is JSON this class builds itself, and a configured
     *                        {@code RedisSerializer} in the consuming service must not change what a
     *                        cached entry looks like on the wire
     * @param objectMapper    used for the envelope and the value
     * @param listeners       the container the invalidation subscriptions are registered on
     * @param metrics         where shared-tier failures are counted
     * @param clock           the same clock the local tier stamps entries with
     * @param applicationName {@code spring.application.name}, the second key segment - two services
     *                        sharing a Redis must not share a key space, and the startup validator refuses
     *                        a shared tier without it
     */
    public RedisSharedCacheTier(StringRedisTemplate redis,
                                ObjectMapper objectMapper,
                                RedisMessageListenerContainer listeners,
                                CacheMetrics metrics,
                                Clock clock,
                                String applicationName) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.listeners = listeners;
        this.metrics = metrics;
        this.clock = clock;
        this.applicationName = applicationName;
    }

    @Override
    public <V> SharedCacheView<V> viewOf(CacheSettings settings, Class<V> valueType) {
        RedisSharedCacheView<V> view = new RedisSharedCacheView<>(
                redis, objectMapper, listeners, metrics, clock, applicationName, settings, valueType);
        view.verifyValueShape();
        return view;
    }
}
