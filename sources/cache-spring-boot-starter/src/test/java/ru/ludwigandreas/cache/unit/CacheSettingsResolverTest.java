package ru.ludwigandreas.cache.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.CachePurpose;
import ru.ludwigandreas.cache.api.CacheSettings;
import ru.ludwigandreas.cache.api.CacheTier;
import ru.ludwigandreas.cache.config.CacheProperties;
import ru.ludwigandreas.cache.config.CacheSettingsResolver;

/** What a cache ends up configured as, given a module's declaration and a deployment's YAML. */
class CacheSettingsResolverTest {

    @Test
    @DisplayName("an unset TTL follows from the purpose: seconds for a security window, minutes for a knob")
    void ttlDefaultFollowsFromPurpose() {
        CacheDefinition<TestKey, TestValue> security =
                CacheDefinition.<TestKey, TestValue>named("a", CachePurpose.SECURITY)
                        .valueType(TestValue.class).keyRenderer(TestKey::id).build();
        CacheDefinition<TestKey, TestValue> performance =
                CacheDefinition.<TestKey, TestValue>named("b", CachePurpose.PERFORMANCE)
                        .valueType(TestValue.class).keyRenderer(TestKey::id).build();
        CacheSettingsResolver resolver = new CacheSettingsResolver(new CacheProperties());

        assertThat(resolver.resolve(security).ttl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(resolver.resolve(performance).ttl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("the deployment's TTL wins over the module's, which wins over the purpose default")
    void threeLayersMerge() {
        CacheProperties properties = CacheFixtures.propertiesFor(CacheFixtures.PROFILES);
        CacheSettingsResolver resolver = new CacheSettingsResolver(properties);

        assertThat(resolver.resolve(CacheFixtures.profiles()).ttl())
                .as("the module's suggestion, with nothing configured").isEqualTo(Duration.ofMinutes(5));

        properties.getCaches().get(CacheFixtures.PROFILES).setTtl(Duration.ofMinutes(30));

        assertThat(resolver.resolve(CacheFixtures.profiles()).ttl()).isEqualTo(Duration.ofMinutes(30));
    }

    /**
     * Local is always present whether it was listed or not.
     *
     * <p>A shared-only cache - a network round trip for a value this process computed a millisecond ago - is
     * not a cache, and letting the configuration express one would be letting it express a mistake.
     */
    @Test
    @DisplayName("the local tier is always first, even if the configuration lists only the shared one")
    void localTierIsAlwaysPresent() {
        CacheProperties properties = CacheFixtures.propertiesFor(CacheFixtures.PROFILES);
        properties.getTiers().getShared().setEnabled(true);
        properties.getCaches().get(CacheFixtures.PROFILES).getTiers().add(CacheTier.SHARED);

        CacheSettings settings = new CacheSettingsResolver(properties).resolve(CacheFixtures.profiles());

        assertThat(settings.tiers()).containsExactly(CacheTier.LOCAL, CacheTier.SHARED);
        assertThat(settings.usesSharedTier()).isTrue();
    }

    @Test
    @DisplayName("a security purpose forces the stale grace to zero whatever was configured")
    void securityPurposeForcesNoStaleGrace() {
        CacheProperties properties = CacheFixtures.propertiesFor(CacheFixtures.GRANTS);
        properties.getCaches().get(CacheFixtures.GRANTS).getStampede()
                .setStaleGrace(Duration.ofMinutes(1));

        CacheSettings settings = new CacheSettingsResolver(properties).resolve(CacheFixtures.grants());

        // The validator refuses this configuration outright, naming the cache. The resolver zeroes it anyway,
        // so that "a security cache never serves past its TTL" is an invariant of the runtime rather than a
        // consequence of a check somewhere else having run.
        assertThat(settings.stampede().staleGrace()).isZero();
        assertThat(settings.physicalTtl()).isEqualTo(settings.ttl());
    }

    @Test
    @DisplayName("an unset negative TTL is a tenth of the value TTL, and never below a second")
    void negativeTtlDefaultsToAFractionOfTheValueTtl() {
        CacheProperties properties = CacheFixtures.propertiesFor(CacheFixtures.PROFILES);
        properties.getCaches().get(CacheFixtures.PROFILES).setTtl(Duration.ofMinutes(10));

        CacheSettings settings = new CacheSettingsResolver(properties).resolve(CacheFixtures.profiles());

        assertThat(settings.negative().ttl()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("a module's scan-eviction requirement survives a configuration that does not mention it")
    void moduleScanRequirementIsNotOverridable() {
        CacheDefinition<TestKey, TestValue> scanning =
                CacheDefinition.<TestKey, TestValue>named(CacheFixtures.PROFILES, CachePurpose.PERFORMANCE)
                        .requiresScanEviction(true)
                        .valueType(TestValue.class).keyRenderer(TestKey::id).build();
        CacheProperties properties = CacheFixtures.propertiesFor(CacheFixtures.PROFILES);

        assertThat(new CacheSettingsResolver(properties).resolve(scanning).scanEviction()).isTrue();
    }

    @Test
    @DisplayName("the key namespace defaults to the cache name at version one")
    void keyNamespaceDefaultsToTheCacheName() {
        CacheSettings settings = new CacheSettingsResolver(new CacheProperties())
                .resolve(CacheFixtures.profiles());

        assertThat(settings.keyNamespace().name()).isEqualTo(CacheFixtures.PROFILES);
        assertThat(settings.keyNamespace().version()).isEqualTo(1);
    }

    /**
     * A YAML key present with an empty value binds to {@code null}, not to the field's initializer.
     *
     * <p>{@code stale-grace:} on a line of its own is what a half-finished edit looks like, and without a
     * substitution here the first TTL computation throws a {@code NullPointerException} several frames away
     * from the line that caused it. The resolved settings are the only thing the runtime reads, so every
     * duration on them has to be non-null whatever the binder produced.
     */
    @Test
    @DisplayName("a duration the binder left null resolves to its default rather than to a later NPE")
    void nullDurationsResolveToDefaults() {
        CacheProperties properties = CacheFixtures.propertiesFor(CacheFixtures.PROFILES);
        CacheProperties.CacheConfig config = properties.getCaches().get(CacheFixtures.PROFILES);
        config.getStampede().setStaleGrace(null);
        config.getStampede().getLease().setTtl(null);
        config.getNegative().setTtl(null);

        CacheSettings settings = new CacheSettingsResolver(properties).resolve(CacheFixtures.profiles());

        assertThat(settings.stampede().staleGrace()).isZero();
        assertThat(settings.stampede().leaseTtl()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.negative().ttl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.physicalTtl()).isEqualTo(Duration.ofMinutes(5));
    }
}
