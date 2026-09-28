package ru.ludwigandreas.cache.api;

/**
 * What a named cache's TTL <em>means</em>, and therefore how the platform is allowed to treat it.
 *
 * <h2>Why this is declared rather than inferred</h2>
 *
 * <p>This module consolidates two caches that were the same class written twice - same SPI shape, same
 * five operations, same {@code (Duration ttl, long maximumSize)} constructor, same paragraph about
 * {@code expireAfterWrite}, same paragraph about the thundering herd. They differed in exactly one way
 * that mattered, and it was not in the code:
 *
 * <ul>
 *   <li>the authority cache's TTL was <b>how long a revoked role keeps working</b>. Sizing it was a
 *       security decision, measured in seconds;</li>
 *   <li>the settings cache's TTL was <b>how long a user sees their old timezone</b>. Sizing it was a
 *       performance decision, measured in minutes, with no privilege attached to being wrong.</li>
 * </ul>
 *
 * <p>A shared module with one default TTL makes one of those two wrong: seconds wastes the settings
 * cache, minutes silently lengthens a revocation window. So the meaning is declared per cache, the
 * default TTL follows from it, and the checks that only make sense for one of the two are applied only
 * to that one.
 *
 * <h2>What the TTL is not</h2>
 *
 * <p>In neither case is the TTL the consistency mechanism - eviction is. A local write evicts after
 * commit, and a projected change event evicts after the projecting transaction commits. The TTL is the
 * backstop for the case those miss (a dropped event, a consumer that was down) so that a wrong value
 * heals on its own instead of persisting until the next restart. A cache with eviction and no TTL never
 * heals from a missed event, which is why a TTL is required rather than optional whatever the purpose.
 */
public enum CachePurpose {

    /**
     * The TTL is a security window: it is how long a revoked grant keeps working.
     *
     * <p>Three things follow, and all three are enforced rather than advised:
     *
     * <ul>
     *   <li>the default TTL is on the scale of seconds, not minutes;</li>
     *   <li>a TTL above {@code ludwig.cache.security-ttl-ceiling} <b>fails startup</b>, naming the
     *       cache. The failure mode is a revoked role that keeps working and nobody noticing, which is
     *       not a class of bug that surfaces on its own;</li>
     *   <li>serving a stale value past expiry is <b>refused</b>. Stale-while-revalidate is a fine
     *       performance affordance and it is exactly the wrong thing here: it extends the window this
     *       TTL exists to bound, by a duration chosen for throughput reasons somewhere else. The same
     *       reasoning refuses the cluster-wide load lease, whose losing replica serves stale.</li>
     * </ul>
     */
    SECURITY("security"),

    /**
     * The TTL is a performance knob: raising it trades freshness for load.
     *
     * <p>Being briefly wrong is a correctness wrinkle with no privilege attached - a user sees the old
     * timezone, a page shows an old digest preference. The default TTL is on the scale of minutes,
     * there is no ceiling, and stale-while-revalidate and the load lease are both available, because
     * what they lengthen is only the wrinkle.
     */
    PERFORMANCE("performance");

    private final String id;

    CachePurpose(String id) {
        this.id = id;
    }

    /** The value written in YAML, and the {@code purpose} tag on every meter for the cache. */
    public String id() {
        return id;
    }

    /** Whether serving a stale value past its logical expiry is permitted for this purpose. */
    public boolean allowsStaleReads() {
        return this == PERFORMANCE;
    }
}
