package ru.ludwigandreas.odatafilter.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.odatafilter.annotation.FilterOperator;
import ru.ludwigandreas.odatafilter.annotation.FilterPolicy;
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
        assertThat(salary.roleRequirements()).containsExactly(Set.of("ROLE_ADMIN"));
        assertThat(salary.permitsRoles(Set.of("ROLE_ADMIN"))).isTrue();
        assertThat(salary.permitsRoles(Set.of("ROLE_USER"))).isFalse();
        assertThat(salary.permitsRoles(Set.of())).isFalse();
    }

    @Test
    void unrestrictedFieldsPermitAnyRole() {
        FilterFieldPolicy name = registry.policyFor(Employee.class).field("name").orElseThrow();
        assertThat(name.permitsRoles(Set.of())).isTrue();
    }

    @Test
    void traversesAnnotatedToOneAssociationsUpToConfiguredDepth() {
        EntityFilterPolicy policy = registry.policyFor(Employee.class);
        assertThat(policy.field("department/name")).isPresent();
        assertThat(policy.field("department/name").get().javaType()).isEqualTo(String.class);
    }

    @Test
    void doesNotTraverseAnAssociationThatWasNotOptedIn() {
        EntityFilterPolicy policy = registry.policyFor(Employee.class);
        assertThat(policy.field("shadowDepartment/name")).isEmpty();
    }

    @Test
    void doesNotExposeTheAssociationItselfAsAComparableField() {
        assertThat(registry.policyFor(Employee.class).field("department")).isEmpty();
    }

    @Test
    void rolesOnTheAssociationGateEveryPathThroughIt() {
        FilterFieldPolicy nested =
                registry.policyFor(Employee.class).field("previousDepartment/name").orElseThrow();

        // Department.name carries no roles of its own; the requirement comes from the association.
        assertThat(nested.permitsRoles(Set.of("ROLE_HR"))).isTrue();
        assertThat(nested.permitsRoles(Set.of("ROLE_USER"))).isFalse();
        assertThat(nested.permitsRoles(Set.of())).isFalse();
    }

    @Test
    void readsTheEntityDefaultOrdering() {
        assertThat(registry.policyFor(Employee.class).defaultOrderBy())
                .singleElement()
                .satisfies(term -> {
                    assertThat(term.propertyPath()).isEqualTo("id");
                    assertThat(term.descending()).isFalse();
                });
    }

    @Test
    void entitiesWithoutADefaultOrderingResolveToAnEmptyList() {
        assertThat(registry.policyFor(UnorderedEntity.class).defaultOrderBy()).isEmpty();
    }

    @Test
    void rejectsADefaultOrderingThatNamesAFieldTheEntityDoesNotHave() {
        assertThatThrownBy(() -> registry.policyFor(MistypedOrderEntity.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("creatdAt")
                .hasMessageContaining("MistypedOrderEntity");
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

    static class UnorderedEntity {
        Long id;
    }

    /** The tie-breaker is server configuration, so a typo in it is a startup-class bug, not a 400. */
    @FilterPolicy(defaultOrderBy = "creatdAt desc")
    static class MistypedOrderEntity {
        Long id;
    }
}
