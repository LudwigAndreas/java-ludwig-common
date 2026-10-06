package ru.ludwigandreas.pat.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.pat.claim.PatClaims;

/**
 * The claim, read the way a verifier reads it: from a map that arrived over the network.
 *
 * <p>Every malformed shape below is a shape an assertion can actually carry, because the claim crosses a JSON
 * boundary and the verifier has no control over what the issuer - or an attacker who has found a way to get
 * an assertion minted - puts in it. The contract these tests pin is that a <b>present but unreadable</b>
 * claim yields empty, and that the caller must treat empty as "refuse" rather than "no attenuation".
 */
class PatClaimsTest {

    @Test
    @DisplayName("a built claim round-trips to its id and its scopes")
    void roundTrips() {
        Map<String, Object> claim = PatClaims.value("pat-123", List.of("orders:read", "orders:write"));

        assertThat(PatClaims.id(claim)).contains("pat-123");
        assertThat(PatClaims.attenuation(claim)).isPresent();
        assertThat(PatClaims.attenuation(claim).orElseThrow().scopes())
                .containsExactly("orders:read", "orders:write");
    }

    @Test
    @DisplayName("the claim carries no role or permission claim, which is the decision it encodes")
    void carriesNoEntitlement() {
        Map<String, Object> claim = PatClaims.value("pat-123", List.of("orders:read"));

        // Two sources of truth for entitlement is the defect local role resolution exists to prevent, so
        // the claim must carry the attenuation and nothing that looks like an authority grant.
        assertThat(claim.keySet()).containsExactlyInAnyOrder(PatClaims.ID, PatClaims.SCOPES);
        assertThat(claim).doesNotContainKeys("roles", "authorities", "permissions", "scope", "realm_access");
    }

    @Test
    @DisplayName("the claim name is namespaced, so it cannot collide with the provider's own claims")
    void claimNameIsNamespaced() {
        assertThat(PatClaims.CLAIM).isEqualTo("ludwig_pat");
    }

    @Test
    @DisplayName("an absent, null or wrongly typed claim yields empty rather than throwing")
    void toleratesMalformedInput() {
        assertThat(PatClaims.id(null)).isEmpty();
        assertThat(PatClaims.attenuation(null)).isEmpty();
        assertThat(PatClaims.id(Map.of())).isEmpty();
        assertThat(PatClaims.attenuation(Map.of())).isEmpty();
        assertThat(PatClaims.id(Map.of(PatClaims.ID, 42))).isEmpty();
        assertThat(PatClaims.id(Map.of(PatClaims.ID, ""))).isEmpty();
        assertThat(PatClaims.attenuation(Map.of(PatClaims.SCOPES, "not-a-list"))).isEmpty();
        assertThat(PatClaims.attenuation(Map.of(PatClaims.SCOPES, List.of()))).isEmpty();
        assertThat(PatClaims.attenuation(Map.of(PatClaims.SCOPES, List.of(1, 2)))).isEmpty();
        assertThat(PatClaims.attenuation(Map.of(PatClaims.SCOPES, List.of("", "  ")))).isEmpty();
    }

    @Test
    @DisplayName("non-string elements are dropped while usable ones survive")
    void dropsUnusableElements() {
        assertThat(PatClaims.attenuation(Map.of(PatClaims.SCOPES, List.of("orders:read", 7, "")))
                .orElseThrow().scopes())
                .containsExactly("orders:read");
    }

    @Test
    @DisplayName("isPresent detects a token-backed assertion and ignores a wrongly typed claim")
    void detectsPresence() {
        assertThat(PatClaims.isPresent(Map.of(PatClaims.CLAIM, Map.of(PatClaims.ID, "x")))).isTrue();
        assertThat(PatClaims.isPresent(Map.of())).isFalse();
        assertThat(PatClaims.isPresent(null)).isFalse();
        assertThat(PatClaims.isPresent(Map.of(PatClaims.CLAIM, "a string"))).isFalse();
    }

    @Test
    @DisplayName("building with a blank id or an empty scope set is refused at the issuer")
    void refusesInvalidConstruction() {
        assertThatThrownBy(() -> PatClaims.value(null, List.of("a")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PatClaims.value("  ", List.of("a")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PatClaims.value("pat-1", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
