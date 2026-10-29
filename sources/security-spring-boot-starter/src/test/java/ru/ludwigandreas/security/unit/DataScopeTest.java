package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.data.DataScope;
import ru.ludwigandreas.security.data.ScopeDimension;

class DataScopeTest {

    @Test
    @DisplayName("a wider grant from one role wins over a narrower one from another")
    void unionPrefersTheWiderGrant() {
        DataScope own = DataScope.restrictedTo(ScopeDimension.OWNER, "user-1");

        assertThat(own.union(DataScope.all()).isUnrestricted()).isTrue();
        assertThat(DataScope.all().union(own).isUnrestricted()).isTrue();
    }

    @Test
    @DisplayName("two restricted grants become alternatives, not an intersection")
    void unionAccumulatesAlternatives() {
        DataScope own = DataScope.restrictedTo(ScopeDimension.OWNER, "user-1");
        DataScope tenant = DataScope.restrictedTo(ScopeDimension.TENANT, "tenant-a");

        DataScope union = own.union(tenant);

        assertThat(union.access()).isEqualTo(DataScope.Access.RESTRICTED);
        assertThat(union.alternatives()).hasSize(2);
    }

    @Test
    @DisplayName("a grant with no value for a dimension can never match, so it is a denial")
    void emptyValueSetCollapsesToNone() {
        DataScope scope = DataScope.restrictedTo(Map.of(ScopeDimension.PARTNER, Set.of()));

        assertThat(scope.denies()).isTrue();
    }

    @Test
    void unionWithADenialChangesNothing() {
        DataScope own = DataScope.restrictedTo(ScopeDimension.OWNER, "user-1");

        assertThat(own.union(DataScope.none())).isEqualTo(own);
        assertThat(DataScope.none().union(own)).isEqualTo(own);
    }

    @Test
    @DisplayName("admits: ALL admits any value, NONE none, a restriction only the values it lists on that dimension")
    void admitsOneValue() {
        DataScope granted = DataScope.restrictedTo(ScopeDimension.OBJECT, "supplier-sync");

        assertThat(DataScope.all().admits(ScopeDimension.OBJECT, "anything")).isTrue();
        assertThat(DataScope.none().admits(ScopeDimension.OBJECT, "supplier-sync")).isFalse();
        assertThat(granted.admits(ScopeDimension.OBJECT, "supplier-sync")).isTrue();
        assertThat(granted.admits(ScopeDimension.OBJECT, "customer-sync")).isFalse();
        assertThat(granted.admits(ScopeDimension.OBJECT, null)).isFalse();
        assertThat(granted.union(DataScope.restrictedTo(ScopeDimension.OBJECT, "customer-sync"))
                .admits(ScopeDimension.OBJECT, "customer-sync")).isTrue();
    }

    @Test
    @DisplayName("admits fails closed: an alternative that also restricts another dimension admits nothing")
    void admitsFailsClosedOnOtherDimensions() {
        DataScope tenantBound = DataScope.restrictedTo(Map.of(
                ScopeDimension.OBJECT, Set.of("supplier-sync"), ScopeDimension.TENANT, Set.of("acme")));

        assertThat(tenantBound.admits(ScopeDimension.OBJECT, "supplier-sync")).isFalse();
        assertThat(DataScope.restrictedTo(ScopeDimension.TENANT, "acme").admits(ScopeDimension.OBJECT, "acme"))
                .isFalse();
    }
}
