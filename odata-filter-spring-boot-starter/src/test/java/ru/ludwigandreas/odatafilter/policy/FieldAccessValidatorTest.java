package ru.ludwigandreas.odatafilter.policy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.odatafilter.ast.FilterNode;
import ru.ludwigandreas.odatafilter.config.ODataFilterProperties;
import ru.ludwigandreas.odatafilter.exception.FilterAccessDeniedException;
import ru.ludwigandreas.odatafilter.exception.FilterDepthExceededException;
import ru.ludwigandreas.odatafilter.exception.UnfilterableFieldException;
import ru.ludwigandreas.odatafilter.parser.ODataFilterParser;
import ru.ludwigandreas.odatafilter.testmodel.Employee;
import ru.ludwigandreas.odatafilter.validation.DepthValidator;
import ru.ludwigandreas.odatafilter.validation.FieldAccessValidator;

class FieldAccessValidatorTest {

    private final ODataFilterParser parser = new ODataFilterParser();
    private final FilterPolicyRegistry registry = new FilterPolicyRegistry(new ODataFilterProperties());
    private final EntityFilterPolicy policy = registry.policyFor(Employee.class);

    @Test
    void allowsFilterableFieldsForAnyCaller() {
        FilterNode node = parser.parse("age gt 18 and name eq 'x'");
        assertThatCode(() -> FieldAccessValidator.validate(policy, node, Set.of())).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownField() {
        FilterNode node = parser.parse("secretNotes eq 'x'");
        assertThatThrownBy(() -> FieldAccessValidator.validate(policy, node, Set.of()))
                .isInstanceOf(UnfilterableFieldException.class);
    }

    @Test
    void rejectsRoleRestrictedFieldWithoutTheRole() {
        FilterNode node = parser.parse("salary gt 100000");
        assertThatThrownBy(() -> FieldAccessValidator.validate(policy, node, Set.of()))
                .isInstanceOf(FilterAccessDeniedException.class);
        assertThatThrownBy(() -> FieldAccessValidator.validate(policy, node, Set.of("ROLE_USER")))
                .isInstanceOf(FilterAccessDeniedException.class);
    }

    @Test
    void allowsRoleRestrictedFieldWithTheRole() {
        FilterNode node = parser.parse("salary gt 100000");
        assertThatCode(() -> FieldAccessValidator.validate(policy, node, Set.of("ROLE_ADMIN")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsFilterExceedingMaxDepth() {
        // Employee's @FilterPolicy caps maxDepth at 4.
        FilterNode node = parser.parse("age gt 1 and age gt 2 and age gt 3 and age gt 4 and age gt 5");
        assertThatThrownBy(() -> DepthValidator.validate(node, policy.maxDepth()))
                .isInstanceOf(FilterDepthExceededException.class);
    }
}
