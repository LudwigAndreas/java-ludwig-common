package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.config.SecurityProperties;
import ru.ludwigandreas.security.data.DataAction;
import ru.ludwigandreas.security.data.DataScope;
import ru.ludwigandreas.security.data.DataScopeProvider;
import ru.ludwigandreas.security.data.ScopeDimension;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationAuthority;
import ru.ludwigandreas.security.operation.OperationCatalog;
import ru.ludwigandreas.security.operation.OperationGrants;
import ru.ludwigandreas.security.operation.Sensitivity;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

class OperationAuthorityTest {

    private static final OperationCatalog CATALOG = new OperationCatalog(List.of(() -> List.of(
            Operation.business("notification.delivery.read", Sensitivity.READ, "k")
                    .on("notification-delivery", DataAction.READ),
            Operation.admin("reconciliation.run.start", Sensitivity.CHANGE, "k"))));

    private static LudwigPrincipal user(String tenant, String... roles) {
        return LudwigPrincipal.builder().subject("alice").type(PrincipalType.USER).tenantId(tenant)
                .roles(List.of(roles)).build();
    }

    private static OperationAuthority authority(DataScopeProvider contributed) {
        Map<String, SecurityProperties.Role> roles = new LinkedHashMap<>();
        roles.put("SUPPORT", OperationGrantsTest.role(Map.of("notification.delivery.read", "TENANT")));
        roles.put("ADMIN", OperationGrantsTest.role(Map.of("notification.delivery.read", "ALL")));
        roles.put("OPS", OperationGrantsTest.role(Map.of("reconciliation.run.start", "OBJECT")));
        return new OperationAuthority(OperationGrants.compile(roles, CATALOG), () -> contributed);
    }

    @Test
    @DisplayName("a role's dimension is bound to the caller, and roles' scopes are united")
    void scopes() {
        OperationAuthority authority = authority(null);

        assertThat(authority.scopeFor(user("acme", "ROLE_SUPPORT"), "notification.delivery.read")
                .admits(ScopeDimension.TENANT, "acme")).isTrue();
        assertThat(authority.scopeFor(user("acme", "ROLE_SUPPORT", "ROLE_ADMIN"), "notification.delivery.read")
                .isUnrestricted()).isTrue();
        assertThat(authority.holds(user("acme", "ROLE_OTHER"), "notification.delivery.read")).isFalse();
    }

    @Test
    @DisplayName("a dimension the caller has no value for denies rather than widens")
    void missingValueDenies() {
        assertThat(authority(null).holds(user(null, "ROLE_SUPPORT"), "notification.delivery.read")).isFalse();
    }

    @Test
    @DisplayName("objects come from per-subject grants, keyed by the operation when it touches no rows")
    void objectsArePerSubject() {
        DataScopeProvider objects = (principal, resource, action) ->
                "reconciliation.run.start".equals(resource) && Operation.INVOKE.equals(action)
                        ? DataScope.restrictedTo(ScopeDimension.OBJECT, "supplier-sync") : DataScope.none();

        assertThat(authority(null).holds(user(null, "ROLE_OPS"), "reconciliation.run.start")).isFalse();
        assertThat(authority(objects).scopeFor(user(null, "ROLE_OPS"), "reconciliation.run.start")
                .admits(ScopeDimension.OBJECT, "supplier-sync")).isTrue();
    }

    @Test
    @DisplayName("a listing keeps only the admitted objects where the scope confines objects, else all of them")
    void admittedObjects() {
        DataScopeProvider objects = (principal, resource, action) ->
                DataScope.restrictedTo(ScopeDimension.OBJECT, "supplier-sync");

        assertThat(authority(objects).admitted(user(null, "ROLE_OPS"), "reconciliation.run.start",
                List.of("customer-sync", "supplier-sync"))).containsExactly("supplier-sync");
        assertThat(authority(null).admitted(user("acme", "ROLE_ADMIN"), "notification.delivery.read",
                List.of("a", "b"))).containsExactly("a", "b");
        assertThat(authority(null).admitted(user(null, "ROLE_NOBODY"), "reconciliation.run.start",
                List.of("a"))).isEmpty();
    }

    @Test
    @DisplayName("held operations are listed with their scope")
    void heldBy() {
        assertThat(authority(null).heldBy(user("acme", "ROLE_SUPPORT")).keySet()).extracting(Operation::id)
                .containsExactly("notification.delivery.read");
    }
}
