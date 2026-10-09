package ru.ludwigandreas.odatafilter.ast;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.odatafilter.parser.ODataFilterParser;

/**
 * The summary must contain every path and operator and <em>no</em> literal, and that is a property of
 * a computation's output rather than of a type graph or a line of source text - so neither ArchUnit nor
 * Checkstyle can express it, and it is encoded here instead. That is this repository's "encode every
 * rule twice" convention: the rule is stated in {@link FilterSummary}'s javadoc and checked here.
 *
 * <p>The negative assertions are the point. A change that started reading {@code Literal} - to hash it,
 * to truncate it, to count an {@code in} list - would keep every positive assertion green.
 */
class FilterSummaryTest {

    private final ODataFilterParser parser = new ODataFilterParser();

    private FilterSummary summarise(String filter) {
        return FilterSummary.of(parser.parse(filter));
    }

    @Test
    @DisplayName("a comparison yields its path and its operator")
    void summarisesAComparison() {
        FilterSummary summary = summarise("Age gt 18");

        assertThat(summary.paths()).containsExactly("Age");
        assertThat(summary.operators()).containsExactly("Age gt");
        assertThat(summary.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("no literal survives, including an e-mail address")
    void carriesNoLiteral() {
        FilterSummary summary = summarise("Name eq 'a.sidorov@example.com'");

        assertThat(summary.paths()).containsExactly("Name");
        assertThat(summary.operators()).containsExactly("Name eq");
        assertThat(summary.toString())
                .doesNotContain("a.sidorov")
                .doesNotContain("example.com")
                .doesNotContain("@");
    }

    @Test
    @DisplayName("no numeric, boolean, date or enum literal survives either")
    void carriesNoScalarLiteral() {
        FilterSummary summary = summarise(
                "Age gt 18 and Price lt 99.50 and Status eq 'ACTIVE' and CreatedAt gt 2024-01-15");

        assertThat(summary.toString())
                .doesNotContain("18")
                .doesNotContain("99.50")
                .doesNotContain("ACTIVE")
                .doesNotContain("2024");
        // Sorted, not in the order the caller wrote them: the walk order is the parse tree's shape.
        assertThat(summary.paths()).containsExactly("Age", "CreatedAt", "Price", "Status");
    }

    @Test
    @DisplayName("and/or/not are walked through, and every leaf's path is collected")
    void walksTheWholeTree() {
        FilterSummary summary = summarise("(Age gt 18 and Name eq 'John') or not (Status eq 'CLOSED')");

        assertThat(summary.paths()).containsExactly("Age", "Name", "Status");
        assertThat(summary.operators()).containsExactlyInAnyOrder("Age gt", "Name eq", "Status eq");
        assertThat(summary.toString()).doesNotContain("John").doesNotContain("CLOSED");
    }

    @Test
    @DisplayName("a string function is recorded by name, without its argument")
    void summarisesAFunction() {
        FilterSummary summary = summarise("contains(Name, 'secret-project')");

        assertThat(summary.operators()).containsExactly("Name contains");
        assertThat(summary.toString()).doesNotContain("secret-project");
    }

    @Test
    @DisplayName("an in list contributes one operator and not its values, nor how many there were")
    void summarisesAnInList() {
        FilterSummary summary = summarise("Id in (1,2,3)");

        assertThat(summary.operators()).containsExactly("Id in");
        // Not even the size: an in list of one says the caller named one specific thing.
        assertThat(summary.toString()).doesNotContain("1").doesNotContain("2").doesNotContain("3");
    }

    @Test
    @DisplayName("a nested path keeps its / separators, so the association traversed is visible")
    void keepsNestedPaths() {
        FilterSummary summary = summarise("Department/Manager/Name eq 'Alice'");

        assertThat(summary.paths()).containsExactly("Department/Manager/Name");
        assertThat(summary.toString()).doesNotContain("Alice");
    }

    @Test
    @DisplayName("one path compared two ways yields two operators and one path")
    void collectsSeveralOperatorsForOnePath() {
        FilterSummary summary = summarise("Age gt 18 and Age lt 65");

        assertThat(summary.paths()).containsExactly("Age");
        assertThat(summary.operators()).containsExactlyInAnyOrder("Age gt", "Age lt");
    }

    @Test
    @DisplayName("term order does not change the summary, so a SIEM rule can match the text")
    void isStableAcrossEquivalentFilters() {
        assertThat(summarise("Age gt 18 and Name eq 'x'").operatorsAsText())
                .isEqualTo(summarise("Name eq 'y' and Age gt 99").operatorsAsText());
        assertThat(summarise("Age gt 18 and Name eq 'x'").pathsAsText()).isEqualTo("Age, Name");
    }

    @Test
    @DisplayName("no filter at all is an empty summary, not a null one")
    void toleratesNoFilter() {
        assertThat(FilterSummary.of(null).isEmpty()).isTrue();
        assertThat(FilterSummary.empty().pathsAsText()).isEmpty();
        assertThat(FilterSummary.empty().operatorsAsText()).isEmpty();
    }
}
