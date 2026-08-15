package ru.ludwigandreas.odatafilter.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.odatafilter.ast.ComparisonNode;
import ru.ludwigandreas.odatafilter.ast.ComparisonOperator;
import ru.ludwigandreas.odatafilter.ast.FilterNode;
import ru.ludwigandreas.odatafilter.ast.FunctionNode;
import ru.ludwigandreas.odatafilter.ast.InNode;
import ru.ludwigandreas.odatafilter.ast.Literal;
import ru.ludwigandreas.odatafilter.ast.LiteralKind;
import ru.ludwigandreas.odatafilter.ast.LogicalNode;
import ru.ludwigandreas.odatafilter.ast.LogicalOperator;
import ru.ludwigandreas.odatafilter.ast.NotNode;
import ru.ludwigandreas.odatafilter.ast.StringFunction;
import ru.ludwigandreas.odatafilter.exception.FilterSyntaxException;

class ODataFilterParserTest {

    private final ODataFilterParser parser = new ODataFilterParser();

    @Test
    void parsesSimpleComparison() {
        FilterNode node = parser.parse("Age gt 18");
        assertThat(node).isEqualTo(new ComparisonNode("Age", ComparisonOperator.GT, new Literal(LiteralKind.INTEGER, "18")));
        assertThat(node.depth()).isEqualTo(1);
    }

    @Test
    void parsesAndOverOr_andBindsTighter() {
        // "A or B and C" must parse as "A or (B and C)"
        FilterNode node = parser.parse("Status eq 'A' or Age gt 1 and Age lt 2");
        assertThat(node).isInstanceOf(LogicalNode.class);
        LogicalNode or = (LogicalNode) node;
        assertThat(or.operator()).isEqualTo(LogicalOperator.OR);
        assertThat(or.left()).isInstanceOf(ComparisonNode.class);
        assertThat(or.right()).isInstanceOf(LogicalNode.class);
        assertThat(((LogicalNode) or.right()).operator()).isEqualTo(LogicalOperator.AND);
    }

    @Test
    void parsesParenthesizedGroupingAndComputesDepth() {
        FilterNode node = parser.parse("(Age gt 18 and Name eq 'John') or Status eq 'ACTIVE'");
        assertThat(node.depth()).isEqualTo(3);
    }

    @Test
    void parsesNot() {
        FilterNode node = parser.parse("not (Status eq 'CLOSED')");
        assertThat(node).isInstanceOf(NotNode.class);
        assertThat(node.depth()).isEqualTo(2);
    }

    @Test
    void parsesFunctionsIncludingExtraWhitespace() {
        FilterNode node = parser.parse("contains( Name , 'foo' )");
        assertThat(node).isEqualTo(new FunctionNode(StringFunction.CONTAINS, "Name", new Literal(LiteralKind.STRING, "foo")));
    }

    @Test
    void parsesNestedPropertyPath() {
        FilterNode node = parser.parse("Department/Manager/Name eq 'Alice'");
        assertThat(((ComparisonNode) node).propertyPath()).isEqualTo("Department/Manager/Name");
    }

    @Test
    void parsesInList() {
        FilterNode node = parser.parse("Id in (1,2,3)");
        assertThat(node).isEqualTo(new InNode("Id", List.of(
                new Literal(LiteralKind.INTEGER, "1"),
                new Literal(LiteralKind.INTEGER, "2"),
                new Literal(LiteralKind.INTEGER, "3"))));
    }

    @Test
    void distinguishesDecimalFromDoubleFromInteger() {
        assertThat(((ComparisonNode) parser.parse("Price gt 10")).value().kind()).isEqualTo(LiteralKind.INTEGER);
        assertThat(((ComparisonNode) parser.parse("Price gt 10.5")).value().kind()).isEqualTo(LiteralKind.DECIMAL);
        assertThat(((ComparisonNode) parser.parse("Price gt 1.5E3")).value().kind()).isEqualTo(LiteralKind.DOUBLE);
    }

    @Test
    void unescapesDoubledSingleQuotes() {
        FilterNode node = parser.parse("Name eq 'it''s a test'");
        assertThat(((ComparisonNode) node).value().rawText()).isEqualTo("it's a test");
    }

    @Test
    void parsesDateAndDateTimeOffsetLiterals() {
        assertThat(((ComparisonNode) parser.parse("CreatedAt gt 2024-01-15")).value().kind()).isEqualTo(LiteralKind.DATE);
        assertThat(((ComparisonNode) parser.parse("CreatedAt gt 2024-01-15T10:30:00Z")).value().kind())
                .isEqualTo(LiteralKind.DATE_TIME_OFFSET);
    }

    @Test
    void rejectsEmptyFilter() {
        assertThatThrownBy(() -> parser.parse("")).isInstanceOf(FilterSyntaxException.class);
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(FilterSyntaxException.class);
    }

    @Test
    void rejectsIncompleteExpression() {
        assertThatThrownBy(() -> parser.parse("Age gt 18 and")).isInstanceOf(FilterSyntaxException.class);
    }

    @Test
    void rejectsUnbalancedParentheses() {
        assertThatThrownBy(() -> parser.parse("((Age gt 18)")).isInstanceOf(FilterSyntaxException.class);
    }

    @Test
    void rejectsTrailingGarbage() {
        assertThatThrownBy(() -> parser.parse("Age gt 18 Name eq 'x'")).isInstanceOf(FilterSyntaxException.class);
    }

    @Test
    void orderByParsesTermsWithDirection() {
        List<OrderByTerm> terms = new ODataOrderByParser().parse("name asc, department/name desc, age");
        assertThat(terms).containsExactly(
                new OrderByTerm("name", false),
                new OrderByTerm("department/name", true),
                new OrderByTerm("age", false));
    }

    @Test
    void orderByRejectsMalformedTerm() {
        assertThatThrownBy(() -> new ODataOrderByParser().parse("name asc,,age"))
                .isInstanceOf(FilterSyntaxException.class);
    }
}
