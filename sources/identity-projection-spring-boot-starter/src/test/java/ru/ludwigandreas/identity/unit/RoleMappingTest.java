package ru.ludwigandreas.identity.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.identity.config.IdentityProjectionProperties;
import ru.ludwigandreas.identity.config.IdentityProjectionProperties.UnmappedRoles;
import ru.ludwigandreas.identity.projection.IdentitySources;

class RoleMappingTest {

    private static IdentitySources sources(UnmappedRoles unmapped) {
        IdentityProjectionProperties.Source staff = new IdentityProjectionProperties.Source();
        staff.setRoleMapping(Map.of("okta-catalog-admins", Set.of("CATALOG_ADMIN", "ROLE_ORDER_AGENT")));
        staff.setUnmappedRoles(unmapped);
        return new IdentitySources(Map.of("staff", staff));
    }

    @Test
    @DisplayName("a mapped group becomes its platform roles, prefix normalised")
    void mapsAGroupToTwoRoles() {
        assertThat(sources(UnmappedRoles.KEEP).mapRoles("staff", Set.of("okta-catalog-admins")))
                .containsExactlyInAnyOrder("CATALOG_ADMIN", "ORDER_AGENT");
    }

    @Test
    @DisplayName("an unmapped role is kept under its own name, without the ROLE_ prefix, by default")
    void keepsUnmappedRoles() {
        assertThat(sources(UnmappedRoles.KEEP).mapRoles("staff", Set.of("ROLE_SUPPORT", "okta-catalog-admins")))
                .containsExactlyInAnyOrder("SUPPORT", "CATALOG_ADMIN", "ORDER_AGENT");
    }

    @Test
    @DisplayName("with DROP an unmapped role is dropped")
    void dropsUnmappedRoles() {
        assertThat(sources(UnmappedRoles.DROP).mapRoles("staff", Set.of("everyone", "okta-catalog-admins")))
                .containsExactlyInAnyOrder("CATALOG_ADMIN", "ORDER_AGENT");
    }

    @Test
    @DisplayName("an empty role set stays empty, so every role is withdrawn")
    void emptyStaysEmpty() {
        assertThat(sources(UnmappedRoles.KEEP).mapRoles("staff", Set.of())).isEmpty();
    }
}
