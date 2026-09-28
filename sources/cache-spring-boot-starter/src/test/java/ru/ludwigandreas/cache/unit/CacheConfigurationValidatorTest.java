package ru.ludwigandreas.cache.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.CachePurpose;
import ru.ludwigandreas.cache.config.CacheConfigurationValidator;
import ru.ludwigandreas.cache.config.CacheProperties;
import ru.ludwigandreas.cache.config.CacheSettingsResolver;
import ru.ludwigandreas.cache.error.CacheConfigurationException;

/**
 * The combinations that are individually valid and jointly wrong.
 *
 * <p>Every one of these produces behaviour that is intermittent, rare and nearly impossible to attribute weeks
 * later - a revoked role that keeps working, a stale value on every replica at once, an administrative eviction
 * that fails on the one request that needs it. None of them is detectable at runtime, which is the whole
 * argument for failing the pod at startup instead.
 */
class CacheConfigurationValidatorTest {

    /**
     * The check the {@link CachePurpose} distinction exists for.
     *
     * <p>Nothing detects a security TTL that is too long once the service is running: no error, no log line, no
     * metric moves. The only symptom is a person retaining access they should not have, for as long as the
     * number somebody typed, and the person who notices is an auditor months later.
     */
    @Test
    @DisplayName("a security-purpose cache above the TTL ceiling fails startup, naming the cache")
    void securityTtlCeilingIsEnforced() {
        CacheProperties properties = CacheFixtures.propertiesFor(CacheFixtures.GRANTS);
        properties.getCaches().get(CacheFixtures.GRANTS).setTtl(Duration.ofMinutes(10));

        assertThatThrownBy(() -> validate(properties, CacheFixtures.grants()))
                .isInstanceOf(CacheConfigurationException.class)
                .hasMessageContaining("grants")
                .hasMessageContaining("purpose=security")
                .hasMessageContaining("how long a revoked grant keeps working");
    }

    @Test
    @DisplayName("the same TTL on a performance-purpose cache is fine - there is no ceiling on a throughput knob")
    void performanceTtlHasNoCeiling() {
        CacheProperties properties = CacheFixtures.propertiesFor(CacheFixtures.PROFILES);
        properties.getCaches().get(CacheFixtures.PROFILES).setTtl(Duration.ofMinutes(10));

        assertThatCode(() -> validate(properties, CacheFixtures.profiles())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a security-purpose cache may not be configured to serve past its TTL")
    void securityCacheRefusesAStaleGrace() {
        CacheProperties properties = CacheFixtures.propertiesFor(CacheFixtures.GRANTS);
        properties.getCaches().get(CacheFixtures.GRANTS).getStampede()
                .setStaleGrace(Duration.ofSeconds(30));

        assertThatThrownBy(() -> validate(properties, CacheFixtures.grants()))
                .hasMessageContaining("grants")
                .hasMessageContaining("stale read past the TTL is exactly what a revocation window forbids");
    }

    /**
     * The API-sharpness decision, enforced at startup rather than on the unlucky request.
     *
     * <p>Eviction by predicate against Redis needs either a {@code KEYS} sweep - which blocks the
     * single-threaded server and has taken production instances down - or a real secondary index, which is a
     * second structure to keep consistent with the first for one administrative call site. Refusing the
     * combination is the honest third option, and refusing it <em>here</em> is what stops it working in the
     * local-only environment it was written in and failing in production.
     */
    @Test
    @DisplayName("scan eviction and the shared tier are refused together, at startup")
    void scanEvictionAndSharedTierAreMutuallyExclusive() {
        CacheProperties properties = CacheFixtures.sharedPropertiesFor(CacheFixtures.PROFILES);
        properties.getCaches().get(CacheFixtures.PROFILES).setScanEviction(true);

        assertThatThrownBy(() -> validate(properties, CacheFixtures.profiles()))
                .hasMessageContaining("profiles")
                .hasMessageContaining("eviction by predicate");
    }

    @Test
    @DisplayName("a module that declares it needs scan eviction cannot have the shared tier added underneath it")
    void aDeclaredScanRequirementAlsoRefusesTheSharedTier() {
        CacheDefinition<TestKey, TestValue> scanning =
                CacheDefinition.<TestKey, TestValue>named(CacheFixtures.PROFILES, CachePurpose.PERFORMANCE)
                        .owner("a-module-with-a-support-path")
                        .requiresScanEviction(true)
                        .valueType(TestValue.class)
                        .keyRenderer(TestKey::id)
                        .build();
        CacheProperties properties = CacheFixtures.sharedPropertiesFor(CacheFixtures.PROFILES);

        assertThatThrownBy(() -> validate(properties, scanning))
                .hasMessageContaining("a-module-with-a-support-path");
    }

    @Test
    @DisplayName("a cache listing the shared tier without the top-level switch fails, and says why two exist")
    void sharedTierNeedsBothSwitches() {
        CacheProperties properties = CacheFixtures.propertiesFor(CacheFixtures.PROFILES);
        properties.getCaches().get(CacheFixtures.PROFILES).getTiers()
                .add(ru.ludwigandreas.cache.api.CacheTier.SHARED);

        assertThatThrownBy(() -> validate(properties, CacheFixtures.profiles()))
                .hasMessageContaining("ludwig.cache.tiers.shared.enabled is false");
    }

    @Test
    @DisplayName("a shared-tier cache with no spring.application.name fails - the key space would be shared")
    void sharedTierNeedsAnApplicationName() {
        CacheProperties properties = CacheFixtures.sharedPropertiesFor(CacheFixtures.PROFILES);
        CacheSettingsResolver resolver = new CacheSettingsResolver(properties);

        assertThatThrownBy(() -> new CacheConfigurationValidator(properties, resolver, "  ")
                .validate(List.of(CacheFixtures.profiles())))
                .hasMessageContaining("spring.application.name");
    }

    /**
     * Turning off the invalidation channel changes what the local TTL means, so the TTL has to be short.
     *
     * <p>With no channel, deleting the shared entry does nothing about the local copies every other replica
     * holds: the local TTL stops being a backstop for a missed event and becomes the only bound on a stale read
     * after a perfectly successful eviction. A five-minute TTL there is a five-minute fleet-wide stale read by
     * nobody's decision, which is what this check turns back into a decision.
     */
    @Test
    @DisplayName("a shared cache with invalidation off must keep its local TTL under the cap")
    void invalidationOffRequiresAShortTtl() {
        CacheProperties properties = CacheFixtures.sharedPropertiesFor(CacheFixtures.PROFILES);
        properties.getCaches().get(CacheFixtures.PROFILES).getShared().setInvalidation(false);
        properties.getCaches().get(CacheFixtures.PROFILES).setTtl(Duration.ofMinutes(5));

        assertThatThrownBy(() -> validate(properties, CacheFixtures.profiles()))
                .hasMessageContaining("local-ttl-cap-without-invalidation");

        properties.getCaches().get(CacheFixtures.PROFILES).setTtl(Duration.ofSeconds(30));
        assertThatCode(() -> validate(properties, CacheFixtures.profiles())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the load lease is refused without a shared tier, on a security cache, and with no stale grace")
    void leaseHasThreePreconditions() {
        CacheProperties localOnly = CacheFixtures.propertiesFor(CacheFixtures.PROFILES);
        localOnly.getCaches().get(CacheFixtures.PROFILES).getStampede().getLease().setEnabled(true);
        assertThatThrownBy(() -> validate(localOnly, CacheFixtures.profiles()))
                .hasMessageContaining("without the shared tier");

        CacheProperties securityCache = CacheFixtures.sharedPropertiesFor(CacheFixtures.GRANTS);
        securityCache.getCaches().get(CacheFixtures.GRANTS).getStampede().getLease().setEnabled(true);
        assertThatThrownBy(() -> validate(securityCache, CacheFixtures.grants()))
                .hasMessageContaining("serve the stale value");

        CacheProperties noGrace = CacheFixtures.sharedPropertiesFor(CacheFixtures.PROFILES);
        noGrace.getCaches().get(CacheFixtures.PROFILES).getStampede().getLease().setEnabled(true);
        assertThatThrownBy(() -> validate(noGrace, CacheFixtures.profiles()))
                .hasMessageContaining("no stale grace");
    }

    @Test
    @DisplayName("a lease held at or beyond the TTL is refused - it would outlive the entry it protects")
    void leaseMustBeShorterThanTheTtl() {
        CacheProperties properties = CacheFixtures.sharedPropertiesFor(CacheFixtures.PROFILES);
        CacheProperties.CacheConfig config = properties.getCaches().get(CacheFixtures.PROFILES);
        config.setTtl(Duration.ofSeconds(10));
        config.getStampede().setStaleGrace(Duration.ofSeconds(5));
        config.getStampede().getLease().setEnabled(true);
        config.getStampede().getLease().setTtl(Duration.ofSeconds(30));

        assertThatThrownBy(() -> validate(properties, CacheFixtures.profiles()))
                .hasMessageContaining("at or beyond its own ttl");
    }

    @Test
    @DisplayName("an absence may not be cached for longer than a value")
    void negativeTtlMayNotExceedTheValueTtl() {
        CacheProperties properties = CacheFixtures.propertiesFor(CacheFixtures.PROFILES);
        CacheProperties.CacheConfig config = properties.getCaches().get(CacheFixtures.PROFILES);
        config.setTtl(Duration.ofMinutes(1));
        config.getNegative().setEnabled(true);
        config.getNegative().setTtl(Duration.ofMinutes(5));

        assertThatThrownBy(() -> validate(properties, CacheFixtures.profiles()))
                .hasMessageContaining("longer than it caches a value");
    }

    @Test
    @DisplayName("an early-refresh threshold outside (0, 1) is refused")
    void refreshThresholdMustBeAFraction() {
        CacheProperties properties = CacheFixtures.propertiesFor(CacheFixtures.PROFILES);
        properties.getCaches().get(CacheFixtures.PROFILES).getStampede().getEarlyRefresh()
                .setThreshold(1.5d);

        assertThatThrownBy(() -> validate(properties, CacheFixtures.profiles()))
                .hasMessageContaining("fraction of the TTL");
    }

    /**
     * The typo, which is otherwise completely silent.
     *
     * <p>A block named {@code profilez} binds, nothing reads it, the cache runs on its defaults, and the
     * operator concludes the TTL they set has no effect on anything. One failed deploy against that
     * investigation is an easy trade.
     */
    @Test
    @DisplayName("a configured cache that no module declares fails startup, listing the ones that exist")
    void anUndeclaredBlockFailsStartup() {
        CacheProperties properties = CacheFixtures.propertiesFor("profilez");

        assertThatThrownBy(() -> validate(properties, CacheFixtures.profiles()))
                .hasMessageContaining("profilez")
                .hasMessageContaining("Declared: profiles");
    }

    @Test
    @DisplayName("every problem is reported together, not one deploy at a time")
    void allProblemsAreReportedAtOnce() {
        CacheProperties properties = CacheFixtures.propertiesFor(CacheFixtures.GRANTS);
        properties.getCaches().put("profilez", new CacheProperties.CacheConfig());
        properties.getCaches().get(CacheFixtures.GRANTS).setTtl(Duration.ofHours(1));
        properties.getCaches().get(CacheFixtures.GRANTS).getStampede()
                .setStaleGrace(Duration.ofSeconds(10));

        assertThatThrownBy(() -> validate(properties, CacheFixtures.grants()))
                .satisfies(thrown -> assertThat(thrown.getMessage().split("\n")).hasSizeGreaterThan(3));
    }

    private void validate(CacheProperties properties, CacheDefinition<?, ?>... definitions) {
        new CacheConfigurationValidator(properties, new CacheSettingsResolver(properties), "test-application")
                .validate(List.of(definitions));
    }
}
