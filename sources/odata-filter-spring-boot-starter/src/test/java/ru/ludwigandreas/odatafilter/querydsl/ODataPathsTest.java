package ru.ludwigandreas.odatafilter.querydsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.PathBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import ru.ludwigandreas.odatafilter.testmodel.Employee;

/**
 * The alias and the path walk these assertions pin were, until this class existed, duplicated by hand
 * in every repository that used this module - so each repository was one edit away from the cross-join
 * described in {@link ODataPaths#requireMatchingAlias}.
 */
class ODataPathsTest {

    @Test
    @DisplayName("the alias is the uncapitalized simple name, which is what a generated Q-type uses")
    void aliasFollowsTheGeneratedConvention() {
        assertThat(ODataPaths.alias(Employee.class)).isEqualTo("employee");
        assertThat(ODataPaths.root(Employee.class).getMetadata().getName()).isEqualTo("employee");
    }

    @Test
    @DisplayName("an ascending and a descending term keep the order the sort gave them")
    void convertsSortTerms() {
        OrderSpecifier<?>[] specifiers = ODataPaths.orderSpecifiers(
                Employee.class, Sort.by(Sort.Order.desc("name"), Sort.Order.asc("age")));

        assertThat(specifiers).hasSize(2);
        assertThat(specifiers[0].isAscending()).isFalse();
        assertThat(specifiers[0].getTarget().toString()).isEqualTo("employee.name");
        assertThat(specifiers[1].isAscending()).isTrue();
        assertThat(specifiers[1].getTarget().toString()).isEqualTo("employee.age");
    }

    @Test
    @DisplayName("a nested path is walked one segment per association hop")
    void walksNestedPaths() {
        OrderSpecifier<?>[] specifiers =
                ODataPaths.orderSpecifiers(Employee.class, Sort.by(Sort.Order.asc("department.name")));

        assertThat(specifiers[0].getTarget().toString()).isEqualTo("employee.department.name");
    }

    @Test
    @DisplayName("both separators are accepted, because OData spells a path with / and Sort with .")
    void acceptsBothSeparators() {
        OrderSpecifier<?> withDot =
                ODataPaths.orderSpecifiers(Employee.class, Sort.by(Sort.Order.asc("department.name")))[0];
        OrderSpecifier<?> withSlash =
                ODataPaths.orderSpecifiers(Employee.class, Sort.by(Sort.Order.asc("department/name")))[0];

        assertThat(withSlash.getTarget().toString()).isEqualTo(withDot.getTarget().toString());
    }

    @Test
    @DisplayName("an unsorted sort yields no ordering rather than a null array")
    void unsortedYieldsNothing() {
        assertThat(ODataPaths.orderSpecifiers(Employee.class, Sort.unsorted())).isEmpty();
    }

    @Test
    @DisplayName("the generated default alias is accepted")
    void acceptsTheConventionalAlias() {
        ODataPaths.requireMatchingAlias(Employee.class, new PathBuilder<>(Employee.class, "employee"));
    }

    @Test
    @DisplayName("a root with an alias of its own is refused, and the message names both aliases")
    void refusesAMismatchedAlias() {
        assertThatThrownBy(() ->
                ODataPaths.requireMatchingAlias(Employee.class, new PathBuilder<>(Employee.class, "e")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'e'")
                .hasMessageContaining("'employee'")
                .hasMessageContaining("cross-join");
    }
}
