package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import ru.ludwigandreas.security.admin.AdminGrants;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;
import ru.ludwigandreas.security.admin.AdminProperties;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

class AdminGrantsTest {

    private final AdminOperationCatalog catalog =
            new AdminOperationCatalog(List.of(AdminOperationCatalogTest.area("outbox")));

    private static LudwigPrincipal user(String... roles) {
        return LudwigPrincipal.builder().subject("alice").type(PrincipalType.USER).roles(Arrays.asList(roles)).build();
    }

    private AdminGrants grants(Map<String, List<String>> configured) {
        return new AdminGrants(configured, catalog);
    }

    @Test
    @DisplayName("a caller holds an operation through any one of its granted roles, and nothing else")
    void holdsThroughAGrantedRole() {
        AdminGrants grants = grants(Map.of(
                "outbox.read", List.of("NOTIFICATION_ADMIN", "NOTIFICATION_SUPPORT"),
                "outbox.operate", List.of("NOTIFICATION_ADMIN")));

        LudwigPrincipal support = user("NOTIFICATION_SUPPORT");
        assertThat(grants.holds(support, "outbox.read")).isTrue();
        assertThat(grants.holds(support, "outbox.operate")).isFalse();
        assertThat(grants.heldBy(support)).extracting(AdminOperation::id).containsExactly("outbox.read");
    }

    @Test
    @DisplayName("a role is matched in its prefixed form whichever way the grant spells it")
    void normalizesRoles() {
        AdminGrants grants = grants(Map.of("outbox.read", List.of("NOTIFICATION_ADMIN"),
                "outbox.operate", List.of("ROLE_NOTIFICATION_ADMIN")));

        LudwigPrincipal admin = user("ROLE_NOTIFICATION_ADMIN");
        assertThat(grants.holds(admin, "outbox.read")).isTrue();
        assertThat(grants.holds(admin, "outbox.operate")).isTrue();
    }

    @Test
    @DisplayName("a higher tier does not imply a lower one")
    void noTierImplication() {
        AdminGrants grants = grants(Map.of("outbox.privileged", List.of("NOTIFICATION_ADMIN")));

        assertThat(grants.holds(user("NOTIFICATION_ADMIN"), "outbox.read")).isFalse();
    }

    @Test
    @DisplayName("an operation nobody was granted is refused for everyone and reported as ungranted")
    void ungrantedOperations() {
        AdminGrants grants = grants(Map.of("outbox.read", List.of("NOTIFICATION_ADMIN")));

        assertThat(grants.holds(user("NOTIFICATION_ADMIN", "ANYTHING"), "outbox.privileged")).isFalse();
        assertThat(grants.ungranted()).containsExactly("outbox.operate", "outbox.privileged");
    }

    @Test
    @DisplayName("a misspelled operation id fails startup and lists what the area does declare")
    void refusesAnUnknownKey() {
        assertThatThrownBy(() -> grants(Map.of("outbox.opreate", List.of("NOTIFICATION_ADMIN"))))
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("'outbox.opreate'")
                .hasMessageContaining("[outbox.operate, outbox.privileged, outbox.read]");
    }

    @Test
    @DisplayName("an empty role list and a blank role each fail startup")
    void refusesEmptyAndBlankRoles() {
        assertThatThrownBy(() -> grants(Map.of("outbox.read", List.of())))
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("outbox.read lists no role");
        assertThatThrownBy(() -> grants(Map.of("outbox.read", List.of("NOTIFICATION_ADMIN", " "))))
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("blank role");
    }

    @Test
    @DisplayName("a dotted key binds whole from YAML-shaped and bracketed sources alike")
    void bindsDottedKeys() {
        Map<String, String> source = new LinkedHashMap<>();
        // What a YAML 'outbox.read: [A, B]' flattens to, and the bracket form a .properties file needs.
        source.put("ludwig.admin.grants.outbox.read[0]", "NOTIFICATION_ADMIN");
        source.put("ludwig.admin.grants.outbox.read[1]", "NOTIFICATION_SUPPORT");
        source.put("ludwig.admin.grants[outbox.operate]", "NOTIFICATION_ADMIN");

        AdminProperties properties = new Binder(new MapConfigurationPropertySource(source))
                .bind("ludwig.admin", Bindable.of(AdminProperties.class)).get();

        assertThat(properties.getGrants())
                .containsEntry("outbox.read", List.of("NOTIFICATION_ADMIN", "NOTIFICATION_SUPPORT"))
                .containsEntry("outbox.operate", List.of("NOTIFICATION_ADMIN"));
        assertThat(grants(properties.getGrants()).holds(user("NOTIFICATION_SUPPORT"), "outbox.read")).isTrue();
    }
}
