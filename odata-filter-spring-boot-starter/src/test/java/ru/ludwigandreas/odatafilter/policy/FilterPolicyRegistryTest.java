package ru.ludwigandreas.odatafilter.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.odatafilter.annotation.FilterOperator;
import ru.ludwigandreas.odatafilter.config.ODataFilterProperties;
import ru.ludwigandreas.odatafilter.testmodel.Employee;

class FilterPolicyRegistryTest {

    private final FilterPolicyRegistry registry = new FilterPolicyRegistry(new ODataFilterProperties());

    @Test
    void appliesEntityLevelOverridesFromFilterPolicyAnnotation() {
        EntityFilterPolicy policy = registry.policyFor(Employee.class);
        assertThat(policy.maxDepth()).isEqualTo(4);
        assertThat(policy.maxPageSize()).isEqualTo(50);
        assertThat(policy.defaultPageSize()).isEqualTo(10);
    }

    @Test
    void exposesDirectFilterableFields() {
        EntityFilterPolicy policy = registry.policyFor(Employee.class);
        assertThat(policy.field("name")).isPresent();
        assertThat(policy.field("age")).isPresent();
        assertThat(policy.field("age").get().javaType()).isEqualTo(Integer.class);
        assertThat(policy.field("salary").get().javaType()).isEqualTo(BigDecimal.class);
    }

    @Test
    void hidesFieldsWithoutTheAnnotation() {
        EntityFilterPolicy policy = registry.policyFor(Employee.class);
        assertThat(policy.field("secretNotes")).isEmpty();
        assertThat(policy.field("id")).isEmpty();
    }

    @Test
    void restrictsSalaryToAdminRole() {
        FilterFieldPolicy salary = registry.policyFor(Employee.class).field("salary").orElseThrow();
        assertThat(salary.allowedRoles()).containsExactly("ROLE_ADMIN");
        assertThat(salary.permitsRoles(java.util.Set.of("ROLE_ADMIN"))).isTrue();
        assertThat(salary.permitsRoles(java.util.Set.of("ROLE_USER"))).isFalse();
        assertThat(salary.permitsRoles(java.util.Set.of())).isFalse();
    }

    @Test
    void unrestrictedFieldsPermitAnyRole() {
        FilterFieldPolicy name = registry.policyFor(Employee.class).field("name").orElseThrow();
        assertThat(name.permitsRoles(java.util.Set.of())).isTrue();
    }

    @Test
    void traversesToOneAssociationsUpToConfiguredDepth() {
        EntityFilterPolicy policy = registry.policyFor(Employee.class);
        assertThat(policy.field("department/name")).isPresent();
        assertThat(policy.field("department/name").get().javaType()).isEqualTo(String.class);
    }

    @Test
    void defaultOperatorSetIncludesEveryOperator() {
        FilterFieldPolicy name = registry.policyFor(Employee.class).field("name").orElseThrow();
        assertThat(name.allowedOperators()).contains(
                FilterOperator.EQ, FilterOperator.CONTAINS, FilterOperator.STARTSWITH, FilterOperator.IN);
    }

    @Test
    void cachesResolvedPolicyPerClass() {
        EntityFilterPolicy first = registry.policyFor(Employee.class);
        EntityFilterPolicy second = registry.policyFor(Employee.class);
        assertThat(first).isSameAs(second);
    }
}
