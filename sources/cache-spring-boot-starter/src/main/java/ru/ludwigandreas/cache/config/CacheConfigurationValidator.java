package ru.ludwigandreas.cache.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.CachePurpose;
import ru.ludwigandreas.cache.api.CacheSettings;
import ru.ludwigandreas.cache.error.CacheConfigurationException;

/**
 * Refuses to start on a caching configuration that is individually valid and jointly wrong.
 *
 * <p>Bean Validation checks one field at a time, which catches a negative pool size and misses every
 * interesting mistake here. Everything below is a relationship between two settings, and every one of them
 * produces behaviour that is intermittent, rare and nearly impossible to attribute weeks after the change
 * that caused it - a revoked role that keeps working, a stale value on every replica at once, a
 * deserialization into a shape that has moved. Failing the pod at startup is the cheapest way to learn
 * about them, and it follows {@code NotificationConfigurationValidator}'s example: collect every problem
 * and report them together, because an operator fixing a configuration wants the whole list.
 *
 * <p>Each message states what breaks rather than what is wrong, because the person reading it is deploying
 * at the time and needs to know whether to roll back.
 */
@Slf4j
public class CacheConfigurationValidator {

    private final CacheProperties properties;
    private final CacheSettingsResolver resolver;
    private final String applicationName;

    /**
     * @param properties      the bound {@code ludwig.cache} tree
     * @param resolver        produces the merged view each check reads
     * @param applicationName {@code spring.application.name}, which the shared tier's key space needs
     */
    public CacheConfigurationValidator(CacheProperties properties, CacheSettingsResolver resolver,
                                       String applicationName) {
        this.properties = properties;
        this.resolver = resolver;
        this.applicationName = applicationName;
    }

    /**
     * Checks every declared cache and every configured block.
     *
     * <p>Called from the registry's constructor rather than from a {@code @PostConstruct}, so that it cannot
     * run after a module has already been handed a cache: the definitions are plain data beans with no
     * dependency on the registry, so Spring creates all of them first, and the registry is therefore the one
     * place where the whole set is known before anything is built from it.
     *
     * @param definitions every cache declared by a module on the classpath
     * @throws CacheConfigurationException listing every problem found, rather than only the first
     */
    public void validate(Collection<CacheDefinition<?, ?>> definitions) {
        List<String> problems = new ArrayList<>();
        Set<String> declared = new LinkedHashSet<>();
        for (CacheDefinition<?, ?> definition : definitions) {
            declared.add(definition.name());
        }
        checkForUndeclaredBlocks(declared, problems);
        for (CacheDefinition<?, ?> definition : definitions) {
            check(definition, resolver.resolve(definition), problems);
        }
        if (!problems.isEmpty()) {
            throw new CacheConfigurationException(CacheProperties.PREFIX
                    + " configuration is unsafe:\n  - " + String.join("\n  - ", problems));
        }
        log.info("Cache configuration validated: {} cache(s) declared ({}), shared tier {}",
                declared.size(), String.join(", ", declared),
                properties.getTiers().getShared().isEnabled() ? "enabled" : "disabled");
    }

    /**
     * Every {@code ludwig.cache.caches} key belongs to a cache some module declared.
     *
     * <p>The typo check, and the one direction of it that is otherwise completely silent: a block named
     * {@code authoritys} binds, nothing reads it, the cache runs on its defaults, and the operator concludes
     * the TTL they set has no effect on anything. Failing here costs one deploy and saves that
     * investigation.
     */
    private void checkForUndeclaredBlocks(Set<String> declared, List<String> problems) {
        for (String configured : properties.getCaches().keySet()) {
            if (!declared.contains(configured)) {
                problems.add("ludwig.cache.caches." + configured + " configures a cache no module declares."
                        + " Declared: " + (declared.isEmpty() ? "(none)" : String.join(", ", declared))
                        + ". A block for a cache nobody declares binds and is then ignored, so the settings"
                        + " in it silently do nothing");
            }
        }
    }

    private void check(CacheDefinition<?, ?> definition, CacheSettings settings, List<String> problems) {
        checkSecurityCeiling(settings, problems);
        checkStaleReads(settings, problems);
        checkScanEviction(settings, problems);
        checkSharedTierIsAvailable(settings, problems);
        checkInvalidationOrShortTtl(settings, problems);
        checkLease(settings, problems);
        checkNegativeTtl(settings, problems);
        checkRefreshThreshold(settings, problems);
        warnOnDowngradedPurpose(definition, settings);
    }

    /**
     * A security-purpose TTL is a revocation window and must stay inside the ceiling.
     *
     * <p>This is the check the whole {@link CachePurpose} distinction exists for. Nothing detects a TTL that
     * is too long at runtime: no error, no log line, no metric moves. The only symptom is a person keeping
     * access they should not have, for as long as the number somebody typed, and the person who notices is
     * an auditor months later.
     */
    private void checkSecurityCeiling(CacheSettings settings, List<String> problems) {
        Duration ceiling = properties.getSecurityTtlCeiling();
        if (ceiling == null) {
            // A present-but-empty `security-ttl-ceiling:` binds to null, which would silently remove the one
            // check that exists for a revocation window. Reported rather than defaulted, because guessing a
            // ceiling on somebody's behalf is the same silence in a different place.
            problems.add("ludwig.cache.security-ttl-ceiling is set to an empty value. It is the only check on"
                    + " how long a revoked grant may keep working; remove the key to take the default, or give"
                    + " it a duration");
            return;
        }
        if (settings.purpose() == CachePurpose.SECURITY && settings.ttl().compareTo(ceiling) > 0) {
            problems.add("cache '" + settings.name() + "' declares purpose=security with ttl "
                    + settings.ttl() + ", above ludwig.cache.security-ttl-ceiling (" + ceiling + "). That"
                    + " TTL is how long a revoked grant keeps working, and nothing at runtime will ever"
                    + " report it - raise the ceiling deliberately, or lower the TTL");
        }
    }

    /**
     * A security-purpose cache may not be configured to serve past its TTL.
     *
     * <p>Refused rather than quietly forced to zero. The person who wrote {@code stale-grace: 30s} on an
     * authority cache believed it was a throughput setting; telling them it is a revocation window is worth
     * a failed deploy.
     *
     * <p>Reads the raw configuration rather than the resolved settings, and has to: the resolver has already
     * forced the grace to zero for a security purpose, because that invariant of the runtime must not depend
     * on a validator elsewhere having run. So the resolved value can never be non-zero here, and the mistake
     * is only visible in what the deployment actually wrote.
     */
    private void checkStaleReads(CacheSettings settings, List<String> problems) {
        CacheProperties.CacheConfig config = properties.getCaches().get(settings.name());
        Duration declaredGrace = config == null
                ? Duration.ZERO
                : config.getStampede().getStaleGrace();
        if (settings.purpose() == CachePurpose.SECURITY && !declaredGrace.isZero()) {
            problems.add("cache '" + settings.name() + "' declares purpose=security and a stale grace of "
                    + declaredGrace + ". A stale read past the TTL is exactly what a"
                    + " revocation window forbids; stale-while-revalidate is a performance affordance and"
                    + " this TTL is not a performance setting");
        }
    }

    /**
     * Scan eviction and the shared tier are mutually exclusive.
     *
     * <p>The two ways to support a predicate eviction against Redis are a {@code KEYS} sweep, which blocks
     * the single-threaded server for its whole duration and has taken production instances down, and a real
     * secondary index, which is a second data structure to keep consistent with the first for the sake of
     * one administrative call site. Refusing the combination at startup is the third option and the honest
     * one: the alternative is that it works in the local-only environment it was written in and fails on
     * the request that first needs it in production.
     */
    private void checkScanEviction(CacheSettings settings, List<String> problems) {
        if (settings.scanEviction() && settings.usesSharedTier()) {
            problems.add("cache '" + settings.name() + "' needs eviction by predicate (declared by "
                    + settings.owner() + ", or configured as scan-eviction: true) and also lists the shared"
                    + " tier. Eviction by predicate cannot be done against Redis without a KEYS sweep or a"
                    + " secondary index; evict by exact key, or keep this cache local-only");
        }
    }

    private void checkSharedTierIsAvailable(CacheSettings settings, List<String> problems) {
        if (!settings.usesSharedTier()) {
            return;
        }
        if (!properties.getTiers().getShared().isEnabled()) {
            problems.add("cache '" + settings.name() + "' lists the shared tier, but"
                    + " ludwig.cache.tiers.shared.enabled is false. Two switches are needed on purpose, so"
                    + " that copying a cache block from a developer's YAML into a chart does not silently"
                    + " start using Redis");
        }
        if (applicationName == null || applicationName.isBlank()) {
            problems.add("cache '" + settings.name() + "' lists the shared tier, but"
                    + " spring.application.name is not set. It is the second segment of every shared key,"
                    + " and without it two services sharing a Redis would share one key space with two"
                    + " value types in it");
        }
    }

    /**
     * A shared cache with invalidation off has to keep its local TTL short.
     *
     * <p>The eviction fan-out has three parts: drop the local entry here, delete the shared entry, and tell
     * the other replicas to drop theirs. Turning the third one off is defensible - it is one fewer moving
     * part - but it changes what the local TTL means: it stops being a backstop for a missed event and
     * becomes the <em>only</em> bound on every other replica's stale copy after a perfectly successful
     * eviction. A five-minute local TTL in that configuration is a five-minute stale read, fleet-wide, by
     * design and by nobody's decision. Requiring the short TTL is how the choice stays a choice.
     */
    private void checkInvalidationOrShortTtl(CacheSettings settings, List<String> problems) {
        if (!settings.usesSharedTier() || settings.shared().invalidationEnabled()) {
            return;
        }
        Duration cap = properties.getTiers().getShared().getLocalTtlCapWithoutInvalidation();
        if (cap != null && settings.ttl().compareTo(cap) > 0) {
            problems.add("cache '" + settings.name() + "' uses the shared tier with invalidation turned off"
                    + " and a ttl of " + settings.ttl() + ", above"
                    + " ludwig.cache.tiers.shared.local-ttl-cap-without-invalidation (" + cap + "). With no"
                    + " invalidation channel, that TTL is the only bound on every other replica's stale"
                    + " local copy after a successful eviction");
        }
    }

    private void checkLease(CacheSettings settings, List<String> problems) {
        if (!settings.stampede().leaseEnabled()) {
            return;
        }
        if (!settings.usesSharedTier()) {
            problems.add("cache '" + settings.name() + "' enables the cluster-wide load lease without the"
                    + " shared tier. The lease is a Redis SET NX; with no shared tier there is nowhere to"
                    + " take it, and per-process coalescing already covers the concurrency inside one JVM");
        }
        if (settings.purpose() == CachePurpose.SECURITY) {
            problems.add("cache '" + settings.name() + "' enables the cluster-wide load lease with"
                    + " purpose=security. The lease works by having the losing replica serve the stale"
                    + " value, which is what a revocation window forbids - use the early refresh, which"
                    + " reloads inside the TTL instead of reading past it");
        }
        if (settings.stampede().staleGrace().isZero()) {
            problems.add("cache '" + settings.name() + "' enables the cluster-wide load lease with no stale"
                    + " grace. A loser with nothing stale to serve loads anyway, so the lease is a Redis"
                    + " round trip per miss that changes nothing");
        }
        if (settings.stampede().leaseTtl().compareTo(settings.ttl()) >= 0) {
            problems.add("cache '" + settings.name() + "' holds its load lease for "
                    + settings.stampede().leaseTtl() + ", at or beyond its own ttl of " + settings.ttl()
                    + ". A lease that outlives the entry it protects keeps the cluster from reloading a key"
                    + " that has already expired everywhere");
        }
    }

    private void checkNegativeTtl(CacheSettings settings, List<String> problems) {
        if (settings.negative().enabled()
                && settings.negative().ttl().compareTo(settings.ttl()) > 0) {
            problems.add("cache '" + settings.name() + "' caches an absence for "
                    + settings.negative().ttl() + ", longer than it caches a value (" + settings.ttl()
                    + "). An absence is the answer most likely to have just stopped being true - it is the"
                    + " one the caller who created the row is about to contradict");
        }
    }

    private void checkRefreshThreshold(CacheSettings settings, List<String> problems) {
        double threshold = settings.stampede().earlyRefreshThreshold();
        if (settings.stampede().earlyRefreshEnabled() && (threshold <= 0 || threshold >= 1)) {
            problems.add("cache '" + settings.name() + "' sets an early-refresh threshold of " + threshold
                    + "; it is a fraction of the TTL and has to be strictly between 0 and 1. At 0 every read"
                    + " of an entry is a refresh candidate, and at 1 the window is empty");
        }
    }

    /**
     * Notes, without refusing, that a deployment has overridden a module's security purpose downward.
     *
     * <p>Not an error: the module's declaration is a default, and a deployment may know something the module
     * does not. But it removes the TTL ceiling and permits stale reads on a cache whose author said its TTL
     * is a revocation window, so it should appear in the startup log of the pod that does it.
     */
    private void warnOnDowngradedPurpose(CacheDefinition<?, ?> definition, CacheSettings settings) {
        if (definition.purpose() == CachePurpose.SECURITY
                && settings.purpose() == CachePurpose.PERFORMANCE) {
            log.warn("Cache '{}' was declared by {} as a security cache - its TTL is how long a revoked"
                            + " grant keeps working - and this deployment has overridden its purpose to"
                            + " performance. The TTL ceiling no longer applies to it and stale reads past"
                            + " the TTL are now permitted on it", settings.name(), definition.owner());
        }
    }
}
