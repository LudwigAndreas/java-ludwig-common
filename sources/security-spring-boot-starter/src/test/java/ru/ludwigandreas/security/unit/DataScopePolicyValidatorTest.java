package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.config.SecurityProperties;
import ru.ludwigandreas.security.data.DataScopePolicyValidator;
import ru.ludwigandreas.security.data.DataScopeRegistry;
import ru.ludwigandreas.security.data.ScopePolicies;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/** The administrative-resource rules: no mapping needed, area must exist, tiers only, ALL or NONE per role. */
class DataScopePolicyValidatorTest {

    private static DataScopePolicyValidator validator(Map<String, Map<String, Map<String, String>>> policies) {
        SecurityProperties properties = new SecurityProperties();
        properties.getData().getPolicies().putAll(policies);
        return new DataScopePolicyValidator(ScopePolicies.compile(properties), new DataScopeRegistry(List.of(),
                Set.of()), () -> Set.of("reconciliation", "jobs"));
    }

    @Test
    @DisplayName("an administrative resource with ALL and NONE role entries needs no mapping")
    void allAndNoneAreAccepted() {
        assertThatCode(() -> validator(Map.of("admin.reconciliation", Map.of(
                "operate", Map.of("ROLE_SUPPLY_OPS", "NONE", "ROLE_PLATFORM_ADMIN", "ALL"))))
                .afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a role-level object list is refused: objects are granted per subject")
    void roleLevelObjectsRefused() {
        assertThatThrownBy(() -> validator(Map.of("admin.reconciliation", Map.of(
                "operate", Map.of("ROLE_SUPPLY_OPS", "OBJECT")))).afterPropertiesSet())
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("admin.reconciliation.operate.ROLE_SUPPLY_OPS")
                .hasMessageContaining("granted per subject");
    }

    @Test
    @DisplayName("an area no module declares, and an action that is not a tier, are refused")
    void unknownAreaAndActionRefused() {
        assertThatThrownBy(() -> validator(Map.of("admin.reconcilation", Map.of(
                "operate", Map.of("ROLE_X", "NONE")))).afterPropertiesSet())
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("'reconcilation'");
        assertThatThrownBy(() -> validator(Map.of("admin.jobs", Map.of(
                "run", Map.of("ROLE_X", "NONE")))).afterPropertiesSet())
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("admin.jobs.run names no tier");
    }

    @Test
    @DisplayName("with no administrative surface every admin.* policy is refused")
    void noSurface() {
        SecurityProperties properties = new SecurityProperties();
        properties.getData().getPolicies().put("admin.jobs", Map.of("read", Map.of("ROLE_X", "ALL")));

        assertThatThrownBy(() -> new DataScopePolicyValidator(ScopePolicies.compile(properties),
                new DataScopeRegistry(List.of(), Set.of())).afterPropertiesSet())
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("administrative surface is off");
    }
}
