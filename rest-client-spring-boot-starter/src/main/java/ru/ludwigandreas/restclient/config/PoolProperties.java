package ru.ludwigandreas.restclient.config;

import jakarta.validation.constraints.Positive;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;

/**
 * Connection pool for one named client.
 *
 * <p>Every field is nullable so that "not declared here" stays distinguishable from "declared as the
 * same value the built-in default happens to have" - that distinction is what
 * {@link ClientPropertiesMerger} needs in order to inherit from the {@code defaults} block
 * correctly. The built-in values quoted in each comment are applied by the merger's base layer.
 *
 * <p>Pools are never shared between named clients. Two clients pointing at the same host still get
 * two pools, because the whole point of separating them is that a saturated dependency must not be
 * able to consume the permits another dependency needs. The cost - a few more idle sockets - is
 * trivial next to the failure it prevents.
 *
 * <p>Only the {@code apache} transport implements these settings. The JDK HTTP client exposes no
 * pool configuration at all, and Reactor Netty maps a subset onto its {@code ConnectionProvider};
 * the startup validator reports a pool block that the chosen engine will ignore rather than letting
 * it look effective.
 */
@Getter
@Setter
public class PoolProperties {

    /** Maximum connections across all routes. Built-in default: 50. */
    @Positive
    private Integer maxTotal;

    /**
     * Maximum connections to any one route (scheme + host + port). Built-in default: 20.
     *
     * <p>A client that talks to a single host wants this equal to {@code max-total}; leaving it at
     * the default there caps concurrency at 20 no matter how large the pool is, which presents as
     * a latency cliff that no timeout explains.
     */
    @Positive
    private Integer maxPerRoute;

    /**
     * How long an idle connection is kept before eviction. Built-in default: 30s.
     *
     * <p>Shorter than the shortest idle timeout on the path - a load balancer that silently drops
     * idle connections at 60s is the usual cause of an "unexpected end of stream" that only happens
     * on the first request after a quiet period.
     */
    private Duration idleEviction;

    /** Absolute lifetime of a connection, idle or not. Built-in default: 5m. */
    private Duration timeToLive;

    /**
     * Revalidate a pooled connection that has been idle at least this long before reusing it.
     * Built-in default: 2s.
     *
     * <p>This is the direct defence against a half-open connection: the peer closed it, the socket
     * has not noticed, and the next request is written into a connection that will never answer.
     * Zero validates always (costly), negative disables validation (do not).
     */
    private Duration validateAfterInactivity;

    /**
     * Keep-alive to apply when the server sends no {@code Keep-Alive} header. Built-in default: 1m.
     *
     * <p>A server's own header always wins where it sends one - overriding it upward is how you get
     * connections the peer has already forgotten.
     */
    private Duration keepAlive;
}
