package ru.ludwigandreas.odatafilter.querydsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.querydsl.core.types.Predicate;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.odatafilter.ast.FilterNode;
import ru.ludwigandreas.odatafilter.config.ODataFilterProperties;
import ru.ludwigandreas.odatafilter.exception.FilterSyntaxException;
import ru.ludwigandreas.odatafilter.exception.UnfilterableFieldException;
import ru.ludwigandreas.odatafilter.parser.ODataFilterParser;
import ru.ludwigandreas.odatafilter.policy.EntityFilterPolicy;
import ru.ludwigandreas.odatafilter.policy.FilterPolicyRegistry;
import ru.ludwigandreas.odatafilter.testmodel.Employee;

class PredicateBuilderTest {

    private final ODataFilterParser parser = new ODataFilterParser();
    private final PredicateBuilder builder = new PredicateBuilder();
    private final EntityFilterPolicy policy =
            new FilterPolicyRegistry(new ODataFilterProperties()).policyFor(Employee.class);

    private Predicate build(String filter) {
        FilterNode node = parser.parse(filter);
        return builder.build(policy, node);
    }

    @Test
    void buildsSimpleComparison() {
        assertThat(build("age gt 18").toString()).isEqualTo("employee.age > 18");
    }

    @Test
    void buildsAndOr() {
        assertThat(build("age gt 18 and status eq 'ACTIVE'").toString())
                .isEqualTo("employee.age > 18 && employee.status = ACTIVE");
    }

    @Test
    void buildsNot() {
        assertThat(build("not (status eq 'CLOSED')").toString()).isEqualTo("!(employee.status = CLOSED)");
    }

    @Test
    void buildsContains() {
        assertThat(build("contains(name,'jo')").toString()).contains("employee.name").contains("jo");
    }

    @Test
    void buildsInAsOrOfEquals() {
        assertThat(build("age in (1,2,3)").toString())
                .isEqualTo("employee.age = 1 || employee.age = 2 || employee.age = 3");
    }

    @Test
    void buildsNestedAssociationPath() {
        assertThat(build("department/name eq 'Engineering'").toString())
                .isEqualTo("employee.department.name = Engineering");
    }

    @Test
    void buildsNullComparison() {
        assertThat(build("status eq null").toString()).isEqualTo("employee.status is null");
        assertThat(build("status ne null").toString()).isEqualTo("employee.status is not null");
    }

    @Test
    void coercesDecimalLiteralAgainstBigDecimalField() {
        assertThat(build("salary gt 1000.50").toString()).isEqualTo("employee.salary > 1000.50");
    }

    @Test
    void rejectsUnknownFieldEvenIfPolicyChecksWereSkipped() {
        FilterNode node = parser.parse("secretNotes eq 'x'");
        assertThatThrownBy(() -> builder.build(policy, node)).isInstanceOf(UnfilterableFieldException.class);
    }

    @Test
    void rejectsUnparsableLiteralForFieldType() {
        FilterNode node = parser.parse("age gt 'not-a-number'");
        assertThatThrownBy(() -> builder.build(policy, node)).isInstanceOf(FilterSyntaxException.class);
    }
}
