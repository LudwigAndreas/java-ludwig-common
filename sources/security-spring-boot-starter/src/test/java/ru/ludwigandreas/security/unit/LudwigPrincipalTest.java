package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

class LudwigPrincipalTest {

    private LudwigPrincipal principal(Set<String> roles) {
        return LudwigPrincipal.builder()
                .subject("alice")
                .type(PrincipalType.USER)
                .roles(roles)
                .build();
    }

    /**
     * {@code hasRole('X')} tests for {@code ROLE_X}. A principal carrying a bare role name is refused
     * while visibly holding the role - a failure that reads as a permission bug and gets "fixed" by
     * granting something wider.
     */
    @Test
    @DisplayName("roles are normalized to the ROLE_ prefix whoever built the principal")
    void normalizesRolesRegardlessOfHowTheyWereWritten() {
        assertThat(principal(Set.of("CATALOG_ADMIN")).roles()).containsExactly("ROLE_CATALOG_ADMIN");
        assertThat(principal(Set.of("ROLE_CATALOG_ADMIN")).roles()).containsExactly("ROLE_CATALOG_ADMIN");
        assertThat(principal(Set.of("CATALOG_ADMIN")).hasRole("ROLE_CATALOG_ADMIN")).isTrue();
    }

    @Test
    void dropsBlankRolesRatherThanCarryingAnEmptyAuthority() {
        assertThat(principal(Set.of("CATALOG_ADMIN", "   ")).roles())
                .containsExactly("ROLE_CATALOG_ADMIN");
    }

    @Test
    @DisplayName("the authority list a @PreAuthorize expression sees is built from the same normalized roles")
    void authoritiesMatchTheNormalizedRoles() {
        LudwigAuthentication authentication = new LudwigAuthentication(
                LudwigPrincipal.builder()
                        .subject("alice")
                        .type(PrincipalType.USER)
                        .roles(Set.of("CATALOG_ADMIN"))
                        .permissions(Set.of("order:read"))
                        .build());

        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_CATALOG_ADMIN", "order:read");
        assertThat(authentication.getName()).isEqualTo("alice");
        assertThat(authentication.getCredentials()).as("the raw credential must not be retained").isNull();
    }

    @Test
    void refusesAPrincipalWithNoStableIdentity() {
        assertThatThrownBy(() -> LudwigPrincipal.builder().subject("  ").type(PrincipalType.USER).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LudwigPrincipal.builder().subject("alice").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesImmutableCollections() {
        LudwigPrincipal alice = principal(Set.of("CATALOG_ADMIN"));

        assertThatThrownBy(() -> alice.roles().add("ROLE_SNEAKY"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> alice.attributes().put("tenant", "other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
