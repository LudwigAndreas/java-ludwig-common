package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.cache.api.CachePurpose;
import ru.ludwigandreas.security.authn.pat.PatIntrospectionCaches;

/**
 * The introspection cache declaration, and the one line in it no check can infer.
 *
 * <p>{@code RuleGroup.CACHING} can see that this module builds no Caffeine instance and declares no cache
 * SPI. What it cannot see - and says so in its own javadoc - is whether the declared <em>purpose</em> is
 * the right one, because that is a statement about what the cached value means. So it is asserted here.
 */
class PatIntrospectionCacheTest {

    @Test
    @DisplayName("the purpose is SECURITY, which is what makes the TTL a bounded revocation window")
    void purposeIsSecurity() {
        // For this authentication path the TTL IS how long a revoked token keeps authenticating.
        // PERFORMANCE would have been the easy mistake: right for most caches, and here it removes the
        // startup ceiling and switches on stale-while-revalidate - which reads as a throughput win and
        // extends the exact window this TTL exists to bound. Nothing in the code would have failed.
        assertThat(PatIntrospectionCaches.definition().purpose()).isEqualTo(CachePurpose.SECURITY);
    }

    @Test
    @DisplayName("the default TTL is seconds, because it bounds an incident response")
    void defaultTtlIsShort() {
        assertThat(PatIntrospectionCaches.definition().defaultTtl())
                .isLessThanOrEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("the cache is named and owned, so a meter and a YAML key trace back to this module")
    void declaresNameAndOwner() {
        assertThat(PatIntrospectionCaches.definition().name()).isEqualTo("pat-introspection");
        assertThat(PatIntrospectionCaches.definition().owner())
                .isEqualTo("security-spring-boot-starter");
    }

    @Test
    @DisplayName("the value type carries no credential material, so the cache cannot leak one")
    void valueTypeHoldsNoSecret() {
        // The cached value is the introspection response, which pat-core's own test already proves
        // carries no secret, digest or key id. Asserted again here because the reason differs: there it
        // is about what crosses the wire, here it is about what reaches a shared Redis tier's key space,
        // a heap dump and an operator's cache inspector.
        assertThat(PatIntrospectionCaches.definition().valueType().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("secretDigest", "keyId", "secret");
    }
}
