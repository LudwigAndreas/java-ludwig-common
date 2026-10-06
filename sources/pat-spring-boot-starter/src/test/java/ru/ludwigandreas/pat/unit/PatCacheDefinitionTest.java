package ru.ludwigandreas.pat.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.CachePurpose;
import ru.ludwigandreas.pat.cache.CachedVerification;
import ru.ludwigandreas.pat.cache.PatCaches;

/**
 * The cache declaration, and in particular the one line in it no check can infer.
 *
 * <p>{@code RuleGroup.CACHING} can see that this module builds no Caffeine instance and declares no cache
 * SPI. What it cannot see - and says so in its own javadoc - is whether the declared <em>purpose</em> is the
 * right one, because that is a statement about what the cached value means. So it is asserted here.
 */
class PatCacheDefinitionTest {

    @Test
    @DisplayName("the purpose is SECURITY, which is what makes the TTL a bounded revocation window")
    void purposeIsSecurity() {
        CacheDefinition<String, CachedVerification> definition = PatCaches.definition();

        // PERFORMANCE would have been the easy mistake: right for most caches, and here it removes the
        // startup ceiling and switches on stale-while-revalidate - which reads as a throughput win and
        // extends the exact window this TTL exists to bound. Nothing in the code would have failed.
        assertThat(definition.purpose()).isEqualTo(CachePurpose.SECURITY);
    }

    @Test
    @DisplayName("the default TTL is seconds, not minutes, because it bounds an incident response")
    void defaultTtlIsShort() {
        assertThat(PatCaches.definition().defaultTtl()).isLessThanOrEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("the cache is named and owned, so a meter and a YAML key can be traced back to this module")
    void declaresNameAndOwner() {
        assertThat(PatCaches.definition().name()).isEqualTo(PatCaches.NAME);
        assertThat(PatCaches.definition().owner()).isEqualTo("pat-spring-boot-starter");
    }

    @Test
    @DisplayName("the cached value holds no digest, so the cache cannot leak credential material")
    void cachedValueHoldsNoSecret() {
        CachedVerification value = new CachedVerification(
                "pat-1", "alice", Set.of("orders:read"), Set.of("deploy-service"),
                Instant.now().plusSeconds(60));

        // The reason this is a projection rather than the entity. A cached value may reach a shared Redis
        // tier, a heap dump and an operator's cache inspector; an entity carries the secret digests.
        assertThat(value.toString()).doesNotContain("digest");
        assertThat(CachedVerification.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("secretDigest", "previousSecretDigest", "keyId");
    }

    @Test
    @DisplayName("a cached verification still checks its own expiry, because a token can expire while cached")
    void cachedValueChecksExpiry() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        CachedVerification expiring = new CachedVerification(
                "pat-1", "alice", Set.of("a"), Set.of("svc"), now.plusSeconds(10));

        assertThat(expiring.isExpired(now)).isFalse();
        // The cache TTL bounds how long a revocation takes to apply; an expiry falling inside that window
        // would otherwise keep working until the entry aged out. The cache holds a verification, not a
        // permission.
        assertThat(expiring.isExpired(now.plusSeconds(11))).isTrue();
    }

    @Test
    @DisplayName("a cached verification answers which audiences it permits")
    void cachedValueChecksAudience() {
        CachedVerification value = new CachedVerification(
                "pat-1", "alice", Set.of("a"), Set.of("deploy-service"), null);

        assertThat(value.permits("deploy-service")).isTrue();
        assertThat(value.permits("billing-service")).isFalse();
        assertThat(value.permits(null)).isFalse();
    }

    @Test
    @DisplayName("a non-expiring token is never expired, which is the explicitly-enabled case")
    void nonExpiringIsNeverExpired() {
        CachedVerification value =
                new CachedVerification("pat-1", "alice", Set.of("a"), Set.of("svc"), null);

        assertThat(value.isExpired(Instant.now().plusSeconds(100_000))).isFalse();
    }
}
