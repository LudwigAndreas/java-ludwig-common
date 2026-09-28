package ru.ludwigandreas.cache.api;

import java.time.Duration;
import java.util.List;

/**
 * One cache's configuration after the module's declaration and the deployment's YAML have been merged
 * and validated.
 *
 * <p>Immutable, and the only thing the runtime reads. Keeping the resolved form separate from the bound
 * {@code ludwig.cache} tree means the merge and the cross-field validation happen exactly once, at
 * startup, rather than being re-derived on a hot path - and it means every error message about an unsafe
 * combination is produced in one place, where it can name the cache.
 *
 * @param name         the cache name: the YAML key, the meter tag, the string in every log line
 * @param owner        the artifact that declared it, for error messages
 * @param enabled      {@code false} yields an always-miss cache rather than a {@code null}, so turning a
 *                     cache off never changes a call site
 * @param purpose      what the TTL means, and therefore how it is governed; see {@link CachePurpose}
 * @param ttl          how long an entry stays fresh. The backstop for a missed eviction, not the
 *                     consistency mechanism
 * @param maximumSize  the local entry bound
 * @param tiers        in read order; always begins with {@link CacheTier#LOCAL}
 * @param scanEviction whether {@link LudwigCache#evictByScan} is permitted - opt-in because it is O(n),
 *                     and refused outright alongside the shared tier
 * @param negative     whether and for how long a "there is no such value" answer is cached
 * @param stampede     how concurrent and cluster-wide misses are kept from becoming a herd
 * @param keyNamespace the shared tier's key namespace and its version
 * @param shared       shared-tier behaviour that is not about keys
 */
public record CacheSettings(
        String name,
        String owner,
        boolean enabled,
        CachePurpose purpose,
        Duration ttl,
        long maximumSize,
        List<CacheTier> tiers,
        boolean scanEviction,
        CacheSettings.Negative negative,
        CacheSettings.Stampede stampede,
        CacheSettings.KeyNamespace keyNamespace,
        CacheSettings.Shared shared) {

    /** Defensive copy, so a resolved setting cannot be edited through the list it was built from. */
    public CacheSettings {
        tiers = List.copyOf(tiers);
    }

    /** Whether this cache reads and writes Redis as well as Caffeine. */
    public boolean usesSharedTier() {
        return tiers.contains(CacheTier.SHARED);
    }

    /**
     * How long an entry of this kind stays fresh.
     *
     * @param isNegative whether the entry records an absence
     * @return the negative TTL for an absence, the ordinary TTL otherwise
     */
    public Duration logicalTtl(boolean isNegative) {
        return isNegative ? negative.ttl() : ttl;
    }

    /**
     * How long Caffeine physically holds an entry: the TTL plus the stale grace.
     *
     * <p>The two are separate because "fresh" and "present" are different questions once
     * stale-while-revalidate exists. Caffeine's own {@code expireAfterWrite} is the physical bound; logical
     * freshness is computed from the entry's write time, which is what makes early refresh and lease-loser
     * stale reads possible at all. For a {@link CachePurpose#SECURITY} cache the grace is zero, so the two
     * coincide and there is no window in which a revoked grant can be served past its TTL.
     */
    public Duration physicalTtl() {
        Duration longest = ttl.compareTo(negative.ttl()) >= 0 ? ttl : negative.ttl();
        return longest.plus(stampede.staleGrace());
    }

    /**
     * Whether and for how long an absence is cached.
     *
     * <p>Only an absence. A <em>failure</em> is never cached, at any TTL, and that is not configurable:
     * caching a failure turns a two-second blip into a sustained outage for the keys that were unlucky,
     * and it is self-reinforcing, because the negative entry suppresses the probe that would notice
     * recovery.
     *
     * @param enabled whether {@link CacheLoad.Absent} produces an entry
     * @param ttl     the absence's own, shorter TTL
     */
    public record Negative(boolean enabled, Duration ttl) {
    }

    /**
     * How a herd of concurrent misses is avoided.
     *
     * <p>Per-key coalescing is not here because it is not optional: it is how the cache loads, always.
     * What is configurable is the two answers to the part coalescing cannot solve - Caffeine computes at
     * most once <em>per process</em>, so N replicas still issue N loads on a cold key.
     *
     * @param earlyRefreshEnabled   jittered refresh before expiry. On by default and the one to prefer:
     *                              it needs no coordination, adds no failure mode, and removes the
     *                              synchronised-expiry cliff that creates the herd in the first place
     * @param earlyRefreshThreshold the fraction of the TTL after which an entry becomes a refresh
     *                              candidate, with the probability rising from zero at the threshold to
     *                              one at expiry - which is what spreads the reloads out
     * @param staleGrace            how long past expiry an entry may still be served, to a lease loser or
     *                              while a refresh is in flight. Forced to zero for a security purpose
     * @param leaseEnabled          a short cluster-wide load lease ({@code SET NX PX}) for a genuinely
     *                              expensive load. Opt-in, needs the shared tier, and the loser serves
     *                              the stale value rather than blocking - so it is refused for a security
     *                              purpose, and refused without a stale grace to serve from
     * @param leaseTtl              how long the lease is held, which bounds how long a crashed loader
     *                              keeps the rest of the cluster from loading
     */
    public record Stampede(
            boolean earlyRefreshEnabled,
            double earlyRefreshThreshold,
            Duration staleGrace,
            boolean leaseEnabled,
            Duration leaseTtl) {
    }

    /**
     * The shared tier's key namespace.
     *
     * <p>Keys are {@code ludwig:{app}:{cache}:v{version}:{key}}, and the version is the rolling-deploy
     * safety belt: v1 and v2 pods share one Redis, v2 changes the serialized shape of a cached value, and
     * without a version v1 reads v2's entry and either throws or - worse - deserializes into something
     * subtly wrong.
     *
     * <p>Two things about it are worth knowing before deciding it is optional:
     *
     * <ul>
     *   <li><b>it only matters for the shared tier.</b> Local Caffeine holds object references in one
     *       process and is immune. Somebody whose local cache has never broken will conclude versioning
     *       is ceremony;</li>
     *   <li><b>a hand-maintained integer somebody forgets to bump is worse than no versioning at all</b>,
     *       because it converts a loud failure into a silent one. So forgetting is made loud rather than
     *       hoped against: the shared tier records a structural fingerprint of the value type under the
     *       version, and a version reused with a differently-shaped value type fails startup. See
     *       {@link ru.ludwigandreas.cache.shared.ValueShape}.</li>
     * </ul>
     *
     * @param name    the namespace segment, defaulting to the cache name
     * @param version bumped whenever the serialized shape of the value changes
     */
    public record KeyNamespace(String name, int version) {
    }

    /**
     * Shared-tier behaviour that is not about keys.
     *
     * @param invalidationEnabled whether an eviction is announced on a Redis pub/sub channel so the other
     *                            replicas drop their local copies. On by default with the shared tier,
     *                            because the alternative is that local TTL is the only bound on a stale
     *                            local entry - which is a defensible choice but not a silent one: turning
     *                            this off requires the local TTL to be at or below
     *                            {@code ludwig.cache.tiers.shared.local-ttl-cap-without-invalidation},
     *                            and startup fails otherwise
     * @param evictionRetries     how many times a failed shared eviction is retried before it is counted
     *                            and logged as an incident
     */
    public record Shared(boolean invalidationEnabled, int evictionRetries) {
    }
}
