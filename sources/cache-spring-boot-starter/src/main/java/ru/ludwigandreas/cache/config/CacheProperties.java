package ru.ludwigandreas.cache.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import ru.ludwigandreas.cache.api.CachePurpose;
import ru.ludwigandreas.cache.api.CacheTier;

/**
 * The whole of {@code ludwig.cache}.
 *
 * <p>The shape is {@code rest-client-spring-boot-starter}'s, deliberately: a map of named things under one
 * prefix, where the name is the lookup key, the meter tag and the string in every log line. That model
 * works because a name that nobody declared fails at startup instead of yielding a silently unconfigured
 * instance, and both directions of that check are implemented here - see {@link CacheConfigurationValidator}.
 *
 * <pre>{@code
 * ludwig:
 *   cache:
 *     tiers:
 *       shared:
 *         enabled: true                   # Redis; off unless configured
 *     caches:
 *       authorities:
 *         purpose: security               # seconds-scale default, ceiling enforced, no stale reads
 *         ttl: 30s
 *         maximum-size: 10000
 *         tiers: [local, shared]
 *         key-namespace: { name: authorities, version: 3 }
 *         negative: { enabled: true, ttl: 5s }
 *       settings:
 *         purpose: performance            # minutes-scale default
 *         ttl: 5m
 *         maximum-size: 50000
 *         tiers: [local]
 *         scan-eviction: true
 *         key-namespace: { name: settings, version: 7 }
 * }</pre>
 *
 * <h2>Why almost every field is a wrapper type</h2>
 *
 * <p>Because unset and set-to-the-default must be distinguishable. A cache's TTL default comes from its
 * {@link CachePurpose} - seconds for a security window, minutes for a performance knob - and a primitive
 * {@code long} of zero could not be told from "the deployment did not say". The merge happens once, in
 * {@link CacheSettingsResolver}, and produces the immutable
 * {@link ru.ludwigandreas.cache.api.CacheSettings} the runtime reads.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = CacheProperties.PREFIX)
public class CacheProperties {

    /** The prefix this class binds, quoted in every error message the module produces. */
    public static final String PREFIX = "ludwig.cache";

    /**
     * Master switch. {@code false} registers nothing: no registry, no caches, no meters, no validator.
     *
     * <p>It exists so a service can keep the dependency and turn caching off - in a test slice, in a
     * migration, in a profile that must answer from the store on every call - without the "turn it off"
     * path being "delete the beans and hope nothing autowired them". Every module then receives an
     * always-miss cache and nothing else changes.
     */
    private boolean enabled = true;

    /**
     * The longest TTL a {@link CachePurpose#SECURITY} cache may declare.
     *
     * <p>A ceiling rather than advice, because the failure mode is a revoked role that keeps working and
     * nobody noticing: nothing fails, nothing is logged, and the only symptom is a person retaining access
     * they should not have, for as long as the number somebody typed. Two minutes is already generous for a
     * cache whose eviction is event-driven and whose TTL is only the backstop.
     */
    private Duration securityTtlCeiling = Duration.ofMinutes(2);

    @Valid
    @NestedConfigurationProperty
    private Refresh refresh = new Refresh();

    @Valid
    @NestedConfigurationProperty
    private Tiers tiers = new Tiers();

    /**
     * The named caches, keyed by cache name.
     *
     * <p>A {@code LinkedHashMap}, so a startup log lists the same caches in the same order on every boot.
     * That is not necessarily <em>declaration</em> order: the binder discovers keys through the property
     * sources, and a map assembled from a YAML file, an environment variable and a command-line override has
     * no single declaration order to preserve.
     *
     * <p>A key here that no module declared <b>fails startup</b>. That is the typo case and it is otherwise
     * silent - the block binds, nothing reads it, and the operator concludes the TTL they set does nothing.
     */
    @Valid
    private Map<String, CacheConfig> caches = new LinkedHashMap<>();

    /** The thread pool that runs early refreshes off the request thread. */
    @Getter
    @Setter
    public static class Refresh {

        /**
         * How many threads run early refreshes.
         *
         * <p>Small on purpose. A refresh is a load that nobody is waiting for; sizing this pool for the
         * worst case would be sizing it to reproduce the herd it exists to prevent, on a different thread.
         */
        @Positive
        private int poolSize = 2;

        /**
         * How many refreshes may queue before further ones are dropped.
         *
         * <p>Dropped, not queued indefinitely and not run on the caller. An early refresh is best-effort:
         * the entry it would have refreshed is still fresh, and the ordinary expiry path will load it. A
         * bounded queue with a discard policy is the only arrangement in which a saturated refresh pool
         * cannot slow down a request.
         */
        @Positive
        private int queueCapacity = 1_000;
    }

    /** Settings that apply to a tier rather than to one cache. */
    @Getter
    @Setter
    public static class Tiers {

        @Valid
        @NestedConfigurationProperty
        private Shared shared = new Shared();
    }

    /** The shared Redis tier. */
    @Getter
    @Setter
    public static class Shared {

        /**
         * Whether the shared tier may be used at all.
         *
         * <p>Two switches are needed to reach Redis - this one and {@code shared} in a cache's
         * {@code tiers} - for the reason {@code rest-client}'s TLS switches are paired: a single per-cache
         * line is one edit in one YAML file, and YAML files get copied from a laptop into a Helm chart.
         * A separate, differently-named top-level switch means the copy does not silently work.
         */
        private boolean enabled;

        /**
         * The longest local TTL permitted on a shared-tier cache that has turned invalidation off.
         *
         * <p>Turning off the pub/sub invalidation channel is allowed and is not silent. Without it,
         * deleting the shared entry does nothing about the local copies every other replica holds, so the
         * local TTL becomes the only bound on a stale read after a successful eviction. Startup fails if a
         * cache in that configuration declares a longer TTL, because the alternative - accepting a
         * five-minute stale read as the cost of an operational preference - is a decision somebody should
         * have to make on purpose.
         */
        private Duration localTtlCapWithoutInvalidation = Duration.ofSeconds(60);
    }

    /** One named cache. Every field left unset falls back to the owning module's declaration. */
    @Getter
    @Setter
    public static class CacheConfig {

        /**
         * {@code false} yields an always-miss cache.
         *
         * <p>Not a {@code null} and not a missing bean: turning a cache off never changes a call site.
         */
        private boolean enabled = true;

        /**
         * What this cache's TTL means. Normally left unset - the owning module declares it.
         *
         * <p>Overridable because a deployment may know something the module does not, but overriding it
         * downward (security to performance) removes the TTL ceiling and permits stale reads on a cache
         * whose author said its TTL is a revocation window. The validator logs that at WARN with the cache
         * name; it does not refuse it, because the module's declaration is a default rather than a law.
         */
        private CachePurpose purpose;

        /**
         * How long an entry stays fresh.
         *
         * <p>The backstop for a missed eviction, not the consistency mechanism. Unset, it is seconds for a
         * security purpose and minutes for a performance one.
         */
        private Duration ttl;

        /** The local entry bound. Unset, the owning module's suggestion. */
        @Positive
        private Long maximumSize;

        /**
         * The tiers to use, in read order.
         *
         * <p>Unset, {@code [local]}. {@link CacheTier#LOCAL} is always present whether it is listed or
         * not: a cache that went to the network for a value this process just computed would be a second
         * database, not a cache.
         */
        private List<CacheTier> tiers = new ArrayList<>();

        /**
         * Whether {@link ru.ludwigandreas.cache.api.LudwigCache#evictByScan} is permitted.
         *
         * <p>Opt-in because it walks the whole key set, and <b>refused outright alongside the shared
         * tier</b>: supporting it there would mean a Redis {@code KEYS} sweep, which blocks the
         * single-threaded server and has taken production instances down, or a real secondary index, which
         * is a second structure to keep consistent for one administrative call site. Startup fails on the
         * combination rather than the unlucky request failing later.
         */
        private boolean scanEviction;

        @Valid
        @NestedConfigurationProperty
        private KeyNamespace keyNamespace = new KeyNamespace();

        @Valid
        @NestedConfigurationProperty
        private Negative negative = new Negative();

        @Valid
        @NestedConfigurationProperty
        private Stampede stampede = new Stampede();

        @Valid
        @NestedConfigurationProperty
        private SharedCache shared = new SharedCache();
    }

    /** The shared tier's key namespace for one cache. */
    @Getter
    @Setter
    public static class KeyNamespace {

        /** The namespace segment. Unset, the cache name. */
        private String name;

        /**
         * Bumped whenever the serialized shape of the cached value changes.
         *
         * <p>Only the shared tier is affected - local Caffeine holds object references in one process and
         * is immune - which is why somebody whose local cache has never broken will conclude this is
         * ceremony. Forgetting to bump it is made loud rather than hoped against: the recorded structural
         * fingerprint of the value type is compared at startup, and a version reused across a shape change
         * fails the context. See {@link ru.ludwigandreas.cache.shared.ValueShape}.
         */
        @Positive
        private int version = 1;
    }

    /** Whether an absence is cached. */
    @Getter
    @Setter
    public static class Negative {

        /**
         * Whether a "there is no such value" answer produces an entry.
         *
         * <p>Off by default: a negative entry is a promise that a key does not exist, and the caller who
         * notices it was wrong is usually the one who just created the row. Worth turning on for a lookup
         * that is hammered for keys that genuinely do not exist.
         *
         * <p>This never applies to a <em>failure</em>. A timeout or a 5xx is not cached at any TTL and that
         * is not configurable: caching one turns a two-second blip into a sustained outage for the keys
         * that were unlucky, and it is self-reinforcing, because the negative entry suppresses the probe
         * that would notice recovery.
         */
        private boolean enabled;

        /** The absence's own, shorter TTL. Unset, a tenth of the cache's TTL. */
        private Duration ttl;
    }

    /** How concurrent and cluster-wide misses are kept from becoming a herd. */
    @Getter
    @Setter
    public static class Stampede {

        @Valid
        @NestedConfigurationProperty
        private EarlyRefresh earlyRefresh = new EarlyRefresh();

        /**
         * How long past its TTL an entry may still be served.
         *
         * <p>Only ever served to a replica that lost the load lease. Forced to zero for a security
         * purpose, where serving past the TTL is exactly what the TTL forbids.
         */
        private Duration staleGrace = Duration.ZERO;

        @Valid
        @NestedConfigurationProperty
        private Lease lease = new Lease();
    }

    /** Jittered refresh before expiry: the stampede answer to prefer. */
    @Getter
    @Setter
    public static class EarlyRefresh {

        /**
         * On by default.
         *
         * <p>It is the answer to prefer because it needs no coordination, adds no failure mode and nothing
         * new that can be unavailable - and because it addresses the actual cause. The herd is not caused
         * by concurrency, which per-key coalescing already handles; it is caused by <b>synchronised
         * expiry</b>: every replica cached the hot key at the same moment, during the same deploy, so every
         * replica's entry expires at the same moment too. Refreshing on a rising probability spreads the
         * reloads across the tail of the TTL instead of landing them all on its edge.
         */
        private boolean enabled = true;

        /**
         * The fraction of the TTL after which an entry becomes a refresh candidate.
         *
         * <p>The probability rises from zero at this point to one at expiry, which is what spreads the
         * reloads out. Lower means earlier and more refreshes; higher means a narrower window and a
         * sharper edge.
         */
        private double threshold = 0.75d;
    }

    /** A short cluster-wide load lease, for a genuinely expensive load. */
    @Getter
    @Setter
    public static class Lease {

        /**
         * Off by default, and opt-in per cache.
         *
         * <p>Needs the shared tier, and is refused for a security purpose and for a cache with no stale
         * grace to serve from - a lease whose loser has nothing stale to serve and therefore loads anyway
         * is a Redis round trip per miss that changes nothing.
         *
         * <p>Note what this is deliberately <b>not</b>: {@code job-core}'s {@code RunLock}. That is a
         * leased lock for scheduled work, with a database round trip per acquisition; one per cache miss
         * would cost more than the load it protects. It is the obvious wrong move and somebody will
         * suggest it.
         */
        private boolean enabled;

        /**
         * How long the lease is held.
         *
         * <p>Bounds how long a loader that crashed keeps the rest of the cluster from loading, so it
         * should be a little longer than a slow load and much shorter than the TTL.
         */
        private Duration ttl = Duration.ofSeconds(5);
    }

    /** Shared-tier behaviour for one cache that is not about keys. */
    @Getter
    @Setter
    public static class SharedCache {

        /**
         * Whether an eviction is announced on a Redis pub/sub channel so other replicas drop their local
         * copies.
         *
         * <p>On by default with the shared tier, because the alternative is that the local TTL is the only
         * bound on every other replica's stale copy. Turning it off is a defensible choice and not a silent
         * one: startup then requires the local TTL to be at or below
         * {@code ludwig.cache.tiers.shared.local-ttl-cap-without-invalidation}.
         */
        private boolean invalidation = true;

        /**
         * How many extra attempts a failed shared delete gets before it is counted and logged as an
         * incident.
         *
         * <p>No backoff between them: the delete runs in {@code afterCommit} on the request thread, and a
         * backoff would hold a response open waiting for a Redis that is probably not coming back inside
         * it. The retries cover a connection reaped between the commit and the delete, which is the common
         * case and is fixed by trying again at once.
         */
        @Positive
        private int evictionRetries = 2;
    }
}
