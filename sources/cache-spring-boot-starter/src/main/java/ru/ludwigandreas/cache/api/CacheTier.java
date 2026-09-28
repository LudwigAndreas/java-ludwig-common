package ru.ludwigandreas.cache.api;

/**
 * A place a cached entry can live.
 *
 * <p>The tiers a cache uses are declared in order, and the order is the read order: {@code [local,
 * shared]} reads local first and promotes a shared hit into local; {@code [local]} - the default -
 * never touches the network.
 */
public enum CacheTier {

    /**
     * Caffeine, in this process.
     *
     * <p>Per-process and therefore immune to the whole class of problems the shared tier has: no
     * serialization, so no shape-versioning hazard; no network call, so no failed eviction; no other
     * reader, so no cross-replica invalidation to arrange. Every cache has it, and it is deliberately
     * not possible to declare a cache as shared-only - a network round trip on every read of a value
     * this process just computed is not a cache, it is a second database.
     */
    LOCAL("local"),

    /**
     * Redis, shared by every replica.
     *
     * <p><b>Opt-in, per cache, and the default is off.</b> Most caches in this platform front a
     * database that is already shared, so the shared tier saves a query the database was going to
     * answer from its own buffer cache anyway - and it adds a failure mode that the single-tier
     * arrangement does not have: if evicting the shared entry fails, <em>every</em> replica keeps
     * serving the stale value, where before only the writing replica was wrong and every other
     * replica's TTL was already independent.
     *
     * <p>Worth it for a genuinely expensive load - a report aggregate, a remote partner lookup, a
     * cold-start warm-up that would otherwise be paid once per replica per deploy. Not worth it for a
     * single-row read by primary key.
     */
    SHARED("shared");

    private final String id;

    CacheTier(String id) {
        this.id = id;
    }

    /** The value written in YAML. */
    public String id() {
        return id;
    }
}
