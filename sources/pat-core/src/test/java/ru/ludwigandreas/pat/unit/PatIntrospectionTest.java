package ru.ludwigandreas.pat.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.pat.introspection.PatIntrospectionResponse;

/**
 * The introspection response: what it carries, and - the assertion that matters - what it cannot.
 *
 * <p>The component names are RFC 7662's ({@code sub}, {@code aud}, {@code exp}) because this module has
 * zero dependencies and therefore no Jackson to map a readable Java name onto a wire name. An attempt to
 * add Jackson here failed the build, which is the zero-dependency property working rather than getting in
 * the way - it is what lets the security starter depend on this module at all.
 */
class PatIntrospectionTest {

    @Test
    @DisplayName("an active response round-trips its facts")
    void activeRoundTrips() {
        PatIntrospectionResponse response = PatIntrospectionResponse.active(
                "alice", Set.of("orders:read", "deploy:write"), Set.of("deploy-service"), "pat-42",
                Instant.parse("2026-09-01T00:00:00Z"));

        assertThat(response.active()).isTrue();
        assertThat(response.sub()).isEqualTo("alice");
        assertThat(response.scopes()).containsExactlyInAnyOrder("orders:read", "deploy:write");
        assertThat(response.permits("deploy-service")).isTrue();
        assertThat(response.permits("billing-service")).isFalse();
        assertThat(response.patId()).isEqualTo("pat-42");
    }

    @Test
    @DisplayName("the inactive response carries nothing but active=false")
    void inactiveCarriesNothing() {
        PatIntrospectionResponse response = PatIntrospectionResponse.inactive();

        // Every failure returns this exact object, so no code path can render an inactive response with
        // one more field than another. Anything that distinguished two causes would be an oracle.
        assertThat(response.active()).isFalse();
        assertThat(response.sub()).isNull();
        assertThat(response.scope()).isNull();
        assertThat(response.aud()).isNull();
        assertThat(response.patId()).isNull();
        assertThat(response.exp()).isNull();
        assertThat(response.scopes()).isEmpty();
        assertThat(response.permits("anything")).isFalse();
    }

    @Test
    @DisplayName("no component is a secret, a digest or a key id - checked over the type")
    void carriesNoCredentialMaterial() {
        // Reflective over the record rather than a reading of it, so a field added next year is covered
        // on the day it is written. The key id is excluded alongside the obvious two: it is
        // secret-adjacent lookup material that changes on rotation, so it is useless to a caller and
        // would quietly become the identifier somebody built an integration on.
        for (RecordComponent component : PatIntrospectionResponse.class.getRecordComponents()) {
            String name = component.getName().toLowerCase(java.util.Locale.ROOT);
            assertThat(name)
                    .as("%s must not carry credential material", component.getName())
                    .doesNotContain("secret")
                    .doesNotContain("digest")
                    .doesNotContain("keyid");
        }
    }

    @Test
    @DisplayName("a non-expiring token reports no expiry rather than a sentinel")
    void nonExpiringReportsNull() {
        assertThat(PatIntrospectionResponse.active(
                "alice", Set.of("a"), Set.of("svc"), "pat-1", null).exp()).isNull();
    }
}
