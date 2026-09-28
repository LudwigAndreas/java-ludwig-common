package ru.ludwigandreas.cache.api;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/**
 * What a module tells the platform about one named cache: its types, and the defaults a deployment may
 * override.
 *
 * <h2>Why a module declares this rather than just asking for a cache by name</h2>
 *
 * <p>The configuration model is {@code rest-client-spring-boot-starter}'s, deliberately: a map of named
 * things under one prefix, where the name is the injection key, the meter tag and the string in every
 * log line. The property that makes that model work is that a typo fails at startup rather than
 * producing a silently unconfigured client, and it must carry over - so both directions are checked, and
 * both need this type to exist:
 *
 * <ul>
 *   <li>asking for a cache nobody declared fails immediately, listing the ones that were declared;</li>
 *   <li>a {@code ludwig.cache.caches.<name>} block that matches no declaration <b>fails startup</b>.
 *       That is the typo case, and without this check it is silent: the operator who wrote
 *       {@code authoritys:} would see their TTL ignored and the default applied, with nothing said.</li>
 * </ul>
 *
 * <h2>Why the types are here even for a local-only cache</h2>
 *
 * <p>{@link #valueType()} and {@link #keyRenderer()} are only <em>used</em> by the shared tier -
 * Caffeine holds object references and needs neither. They are required anyway, so that switching a
 * cache to {@code tiers: [local, shared]} is a change to one YAML file and not a change to the module
 * that owns the cache. A definition that could not be promoted without a code change would make the
 * shared tier an architectural decision instead of an operational one.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class CacheDefinition<K, V> {

    private final String name;
    private final String owner;
    private final CachePurpose purpose;
    private final Duration defaultTtl;
    private final long defaultMaximumSize;
    private final Class<V> valueType;
    private final Function<K, String> keyRenderer;
    private final boolean requiresScanEviction;

    private CacheDefinition(Builder<K, V> builder) {
        this.name = builder.name;
        this.owner = builder.owner;
        this.purpose = builder.purpose;
        this.defaultTtl = builder.defaultTtl;
        this.defaultMaximumSize = builder.defaultMaximumSize;
        this.valueType = builder.valueType;
        this.keyRenderer = builder.keyRenderer;
        this.requiresScanEviction = builder.requiresScanEviction;
    }

    /**
     * Starts a definition.
     *
     * @param name    the cache name, which is the YAML key under {@code ludwig.cache.caches} and the
     *                {@code cache} tag on every meter
     * @param purpose what this cache's TTL means; see {@link CachePurpose}, because the two are
     *                governed differently and getting it wrong is the one mistake this module cannot
     *                detect for you
     * @param <K>     the key type
     * @param <V>     the value type
     * @return a builder
     */
    public static <K, V> Builder<K, V> named(String name, CachePurpose purpose) {
        return new Builder<>(name, purpose);
    }

    public String name() {
        return name;
    }

    /**
     * The artifact that declared this cache, for error messages.
     *
     * <p>An operator reading "no cache named 'authorities' is declared" needs to know which dependency
     * was supposed to declare it; the answer is rarely obvious from the name.
     */
    public String owner() {
        return owner;
    }

    public CachePurpose purpose() {
        return purpose;
    }

    /** The module's suggested TTL, overridden by {@code ludwig.cache.caches.<name>.ttl}. */
    public Duration defaultTtl() {
        return defaultTtl;
    }

    /** The module's suggested bound, overridden by {@code ludwig.cache.caches.<name>.maximum-size}. */
    public long defaultMaximumSize() {
        return defaultMaximumSize;
    }

    /**
     * The value's runtime type, used to serialize into the shared tier and to fingerprint its shape.
     *
     * @see ru.ludwigandreas.cache.shared.ValueShape
     */
    public Class<V> valueType() {
        return valueType;
    }

    /**
     * Renders a key into the stable string that becomes part of the shared key.
     *
     * <p>It has to be stable across releases and across replicas, which {@code Object#toString} is not
     * required to be - so each module states it rather than inheriting a default that would work until
     * somebody added a field to a record. Two different keys must never render the same string: the
     * shared tier has no way to notice a collision, and the symptom would be one principal reading
     * another's cached value.
     */
    public Function<K, String> keyRenderer() {
        return keyRenderer;
    }

    /**
     * Whether this module's own call sites need {@link LudwigCache#evictByScan}.
     *
     * <p>Declared by the module rather than configured by the deployment, because it is a property of the
     * code: {@code user-settings} has a support path that holds a principal and not a tenant, and has to
     * evict that principal across every tenant they are cached under. A deployment cannot turn it off - it
     * would break that call site - and it also cannot add the shared tier to such a cache, because eviction
     * by predicate against Redis needs either a {@code KEYS} sweep or a secondary index. The validator
     * refuses the combination at startup, naming this module.
     */
    public boolean requiresScanEviction() {
        return requiresScanEviction;
    }

    @Override
    public String toString() {
        return "CacheDefinition[" + name + ", purpose=" + purpose.id() + ", owner=" + owner + "]";
    }

    /**
     * Collects the parts of a {@link CacheDefinition}.
     *
     * @param <K> the key type
     * @param <V> the value type
     */
    public static final class Builder<K, V> {

        private static final long DEFAULT_MAXIMUM_SIZE = 10_000L;

        private final String name;
        private final CachePurpose purpose;
        private String owner = "unknown";
        private Duration defaultTtl;
        private long defaultMaximumSize = DEFAULT_MAXIMUM_SIZE;
        private Class<V> valueType;
        private Function<K, String> keyRenderer;
        private boolean requiresScanEviction;

        private Builder(String name, CachePurpose purpose) {
            this.name = requireText(name, "a cache name");
            this.purpose = Objects.requireNonNull(purpose, "a cache must declare what its TTL means");
        }

        /** The artifact declaring the cache, quoted in startup errors about it. */
        public Builder<K, V> owner(String value) {
            this.owner = requireText(value, "an owner");
            return this;
        }

        /**
         * The TTL to use when the deployment does not set one.
         *
         * <p>Optional. Left unset, the default follows from the purpose - seconds for
         * {@link CachePurpose#SECURITY}, minutes for {@link CachePurpose#PERFORMANCE} - which is the
         * point of declaring the purpose at all.
         */
        public Builder<K, V> defaultTtl(Duration value) {
            this.defaultTtl = value;
            return this;
        }

        /** The entry bound to use when the deployment does not set one. */
        public Builder<K, V> defaultMaximumSize(long value) {
            this.defaultMaximumSize = value;
            return this;
        }

        /** States that this module calls {@link LudwigCache#evictByScan}; see the accessor for why. */
        public Builder<K, V> requiresScanEviction(boolean value) {
            this.requiresScanEviction = value;
            return this;
        }

        /** The value type, for shared-tier serialization and shape fingerprinting. */
        public Builder<K, V> valueType(Class<V> value) {
            this.valueType = Objects.requireNonNull(value, "a cache must declare its value type");
            return this;
        }

        /** How a key becomes the stable tail of a shared key; see {@link #keyRenderer()}. */
        public Builder<K, V> keyRenderer(Function<K, String> value) {
            this.keyRenderer = Objects.requireNonNull(value, "a cache must declare how keys render");
            return this;
        }

        /**
         * @return the definition
         * @throws IllegalStateException if the value type or the key renderer is missing; see the class
         *                               javadoc for why they are required even local-only
         */
        public CacheDefinition<K, V> build() {
            if (valueType == null || keyRenderer == null) {
                throw new IllegalStateException("Cache '" + name + "' must declare valueType and"
                        + " keyRenderer. Both are required even for a local-only cache, so that adding"
                        + " 'shared' to its tiers stays a change to one YAML file.");
            }
            return new CacheDefinition<>(this);
        }

        private static String requireText(String value, String what) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Expected " + what + ", got '" + value + "'");
            }
            return value;
        }
    }
}
