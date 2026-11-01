package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import ru.ludwigandreas.security.config.SecurityProperties;
import ru.ludwigandreas.security.data.ScopeGrant;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;
import ru.ludwigandreas.security.operation.OperationCatalog;
import ru.ludwigandreas.security.operation.OperationGrants;

class OperationGrantsTest {

    private final OperationCatalog catalog = new OperationCatalog(List.of(OperationCatalogTest.outbox()));

    static SecurityProperties.Role role(Map<String, String> operations, String... includes) {
        SecurityProperties.Role role = new SecurityProperties.Role();
        role.setOperations(new LinkedHashMap<>(operations));
        role.setIncludes(List.of(includes));
        return role;
    }

    private OperationGrants compile(Map<String, SecurityProperties.Role> roles) {
        return OperationGrants.compile(roles, catalog);
    }

    @Test
    @DisplayName("a role holds its own operations and those of every role it includes, transitively")
    void includes() {
        Map<String, SecurityProperties.Role> roles = new LinkedHashMap<>();
        roles.put("SUPPORT", role(Map.of("outbox.message.read", "ALL")));
        roles.put("OPERATOR", role(Map.of("outbox.message.retry", "ALL"), "SUPPORT"));
        roles.put("ROLE_ADMIN", role(Map.of("outbox.message.discard", "ALL"), "OPERATOR"));

        OperationGrants grants = compile(roles);

        assertThat(grants.grantsOf("ADMIN")).containsOnlyKeys("outbox.message.read", "outbox.message.retry",
                "outbox.message.discard");
        assertThat(grants.grantsOf("ROLE_SUPPORT")).containsOnlyKeys("outbox.message.read");
        assertThat(grants.holders("outbox.message.read"))
                .extracting(OperationGrants.Holder::role, OperationGrants.Holder::via)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("ROLE_ADMIN", "ROLE_SUPPORT"),
                        org.assertj.core.groups.Tuple.tuple("ROLE_OPERATOR", "ROLE_SUPPORT"),
                        org.assertj.core.groups.Tuple.tuple("ROLE_SUPPORT", null));
    }

    @Test
    @DisplayName("a scope is ALL or dimensions joined by +")
    void parsesScopes() {
        OperationGrants grants = compile(Map.of("EDITOR", role(Map.of("outbox.message.read", "OWN+TENANT"))));

        ScopeGrant grant = grants.grantsOf("EDITOR").get("outbox.message.read").get(0);
        assertThat(grant.kind()).isEqualTo(ScopeGrant.Kind.RESTRICTED);
        assertThat(grant.dimensions()).hasSize(2);
    }

    @Test
    @DisplayName("an operation no role holds is reported as unheld")
    void unheld() {
        assertThat(compile(Map.of("SUPPORT", role(Map.of("outbox.message.read", "ALL")))).unheld())
                .containsExactly("outbox.message.discard", "outbox.message.retry");
    }

    @Test
    @DisplayName("a misspelled operation fails startup and lists what the area declares")
    void refusesAnUnknownOperation() {
        assertThatThrownBy(() -> compile(Map.of("ADMIN", role(Map.of("outbox.message.dicsard", "ALL")))))
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("outbox.message.dicsard").hasMessageContaining("outbox.message.discard");
    }

    @Test
    @DisplayName("a wildcard and NONE are refused")
    void refusesWildcardsAndNone() {
        assertThatThrownBy(() -> compile(Map.of("ADMIN", role(Map.of("outbox.*", "ALL")))))
                .hasMessageContaining("wildcard");
        assertThatThrownBy(() -> compile(Map.of("ADMIN", role(Map.of("outbox.message.read", "NONE")))))
                .hasMessageContaining("NONE");
    }

    @Test
    @DisplayName("a cycle in includes, and an include of an undefined role, fail startup naming the roles")
    void refusesBadIncludes() {
        Map<String, SecurityProperties.Role> cycle = new LinkedHashMap<>();
        cycle.put("A", role(Map.of(), "B"));
        cycle.put("B", role(Map.of(), "A"));
        assertThatThrownBy(() -> compile(cycle)).hasMessageContaining("cycle")
                .hasMessageContaining("ROLE_A").hasMessageContaining("ROLE_B");
        assertThatThrownBy(() -> compile(Map.of("A", role(Map.of(), "GHOST"))))
                .hasMessageContaining("ROLE_GHOST").hasMessageContaining("not defined");
    }

    @Test
    @DisplayName("a role written twice, once with its prefix, fails startup")
    void refusesARoleDefinedTwice() {
        Map<String, SecurityProperties.Role> roles = new LinkedHashMap<>();
        roles.put("ADMIN", role(Map.of()));
        roles.put("ROLE_ADMIN", role(Map.of()));
        assertThatThrownBy(() -> compile(roles)).hasMessageContaining("a second time");
    }

    @Test
    @DisplayName("a dotted operation key binds whole from YAML-shaped and bracketed sources alike")
    void bindsDottedKeys() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("ludwig.security.roles.SUPPORT.operations.outbox.message.read", "ALL");
        source.put("ludwig.security.roles.ADMIN.operations[outbox.message.discard]", "ALL");
        source.put("ludwig.security.roles.ADMIN.includes[0]", "SUPPORT");

        SecurityProperties properties = new Binder(new MapConfigurationPropertySource(source))
                .bind("ludwig.security", Bindable.of(SecurityProperties.class)).get();

        assertThat(properties.getRoles().get("SUPPORT").getOperations()).containsEntry("outbox.message.read", "ALL");
        assertThat(compile(properties.getRoles()).grantsOf("ADMIN"))
                .containsOnlyKeys("outbox.message.read", "outbox.message.discard");
    }
}
