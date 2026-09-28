package ru.ludwigandreas.cache.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.CachePurpose;
import ru.ludwigandreas.cache.api.CacheSettings;
import ru.ludwigandreas.cache.api.CacheTier;

/**
 * Merges what a module declared with what the deployment configured.
 *
 * <p>Three layers, in this order: the built-in default for the declared {@link CachePurpose}, the owning
 * module's suggestion, then the {@code ludwig.cache.caches.<name>} block. The merge is explicit rather
 * than left to relaxed binding, for the reason {@code ClientPropertiesMerger} exists in
 * {@code rest-client-spring-boot-starter}: relaxed binding has no notion of one source inheriting from
 * another, and would simply have produced a cache with half its fields unset.
 *
 * <p>It runs once, at startup, and produces the immutable {@link CacheSettings} the runtime reads. Nothing
 * on a read path re-derives any of it.
 */
public class CacheSettingsResolver {

    /**
     * A security window's default, in seconds because it is a revocation window.
     *
     * <p>Thirty seconds is already generous for a cache whose eviction is event-driven and whose TTL only
     * covers a dropped event.
     */
    private static final Duration DEFAULT_SECURITY_TTL = Duration.ofSeconds(30);

    /** A performance knob's default, in minutes because being briefly wrong costs nothing but freshness. */
    private static final Duration DEFAULT_PERFORMANCE_TTL = Duration.ofMinutes(5);

    /** An absence's TTL, as a fraction of the value TTL, when the deployment does not set one. */
    private static final int NEGATIVE_TTL_DIVISOR = 10;

    /** The shortest negative TTL the divisor may produce; below this it is not worth an entry. */
    private static final Duration MINIMUM_NEGATIVE_TTL = Duration.ofSeconds(1);

    /** The lease's fallback, for the one case where a bound value can be null; see {@link #orDefault}. */
    private static final Duration DEFAULT_LEASE_TTL = Duration.ofSeconds(5);

    private final CacheProperties properties;

    /** @param properties the bound {@code ludwig.cache} tree */
    public CacheSettingsResolver(CacheProperties properties) {
        this.properties = properties;
    }

    /**
     * The effective configuration of one cache.
     *
     * @param definition the owning module's declaration
     * @return the resolved settings
     */
    public CacheSettings resolve(CacheDefinition<?, ?> definition) {
        CacheProperties.CacheConfig config = properties.getCaches()
                .getOrDefault(definition.name(), new CacheProperties.CacheConfig());

        CachePurpose purpose = config.getPurpose() != null ? config.getPurpose() : definition.purpose();
        Duration ttl = resolveTtl(config, definition, purpose);
        long maximumSize = config.getMaximumSize() != null
                ? config.getMaximumSize()
                : definition.defaultMaximumSize();

        return new CacheSettings(
                definition.name(),
                definition.owner(),
                config.isEnabled(),
                purpose,
                ttl,
                maximumSize,
                resolveTiers(config),
                config.isScanEviction() || definition.requiresScanEviction(),
                resolveNegative(config, ttl),
                resolveStampede(config, purpose),
                resolveNamespace(config, definition),
                new CacheSettings.Shared(config.getShared().isInvalidation(),
                        config.getShared().getEvictionRetries()));
    }

    private Duration resolveTtl(CacheProperties.CacheConfig config, CacheDefinition<?, ?> definition,
                                CachePurpose purpose) {
        if (config.getTtl() != null) {
            return config.getTtl();
        }
        if (definition.defaultTtl() != null) {
            return definition.defaultTtl();
        }
        return purpose == CachePurpose.SECURITY ? DEFAULT_SECURITY_TTL : DEFAULT_PERFORMANCE_TTL;
    }

    /**
     * The tiers, with {@link CacheTier#LOCAL} guaranteed first.
     *
     * <p>Local is added whether it was listed or not. A shared-only cache - a network round trip for a value
     * this process computed a millisecond ago - is not a cache, and letting the configuration express one
     * would be letting it express a mistake.
     */
    private List<CacheTier> resolveTiers(CacheProperties.CacheConfig config) {
        List<CacheTier> tiers = new ArrayList<>();
        tiers.add(CacheTier.LOCAL);
        if (config.getTiers().contains(CacheTier.SHARED)) {
            tiers.add(CacheTier.SHARED);
        }
        return tiers;
    }

    private CacheSettings.Negative resolveNegative(CacheProperties.CacheConfig config, Duration ttl) {
        Duration negativeTtl = config.getNegative().getTtl();
        if (negativeTtl == null || negativeTtl.isZero() || negativeTtl.isNegative()) {
            negativeTtl = ttl.dividedBy(NEGATIVE_TTL_DIVISOR);
            if (negativeTtl.compareTo(MINIMUM_NEGATIVE_TTL) < 0) {
                negativeTtl = MINIMUM_NEGATIVE_TTL;
            }
        }
        return new CacheSettings.Negative(config.getNegative().isEnabled(), negativeTtl);
    }

    /**
     * The stampede settings, with the stale grace forced to zero for a security purpose.
     *
     * <p>Belt and braces: the validator already refuses a security cache that declares a non-zero grace,
     * naming it, so this branch is unreachable through configuration. It is here because "a security cache
     * never serves past its TTL" is an invariant of the runtime and should not depend on a validator
     * elsewhere having run.
     */
    private CacheSettings.Stampede resolveStampede(CacheProperties.CacheConfig config, CachePurpose purpose) {
        CacheProperties.Stampede stampede = config.getStampede();
        Duration declaredGrace = orDefault(stampede.getStaleGrace(), Duration.ZERO);
        Duration staleGrace = purpose.allowsStaleReads() ? declaredGrace : Duration.ZERO;
        return new CacheSettings.Stampede(
                stampede.getEarlyRefresh().isEnabled(),
                stampede.getEarlyRefresh().getThreshold(),
                staleGrace,
                stampede.getLease().isEnabled(),
                orDefault(stampede.getLease().getTtl(), DEFAULT_LEASE_TTL));
    }

    /**
     * Substitutes a default for a duration the binder produced as {@code null}.
     *
     * <p>Not defensive programming for its own sake: a YAML key that is present with an empty value -
     * {@code stale-grace:} on a line of its own, which is what a half-finished edit looks like - binds to
     * {@code null} rather than to the field's initializer. Without this, the first comparison against it
     * throws a {@code NullPointerException} from inside a TTL computation, which is a stack trace several
     * frames away from the line that caused it. The resolved settings are the only thing the runtime reads,
     * so substituting here is enough to keep every one of them non-null.
     */
    private static Duration orDefault(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }

    private CacheSettings.KeyNamespace resolveNamespace(CacheProperties.CacheConfig config,
                                                        CacheDefinition<?, ?> definition) {
        String name = config.getKeyNamespace().getName();
        return new CacheSettings.KeyNamespace(
                name == null || name.isBlank() ? definition.name() : name,
                config.getKeyNamespace().getVersion());
    }
}
