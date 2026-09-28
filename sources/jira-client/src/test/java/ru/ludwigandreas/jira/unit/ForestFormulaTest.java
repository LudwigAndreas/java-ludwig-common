package ru.ludwigandreas.jira.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.jira.error.JiraSerializationException;
import ru.ludwigandreas.jira.structure.model.ForestFormula;
import ru.ludwigandreas.jira.structure.model.ForestNode;
import ru.ludwigandreas.jira.structure.model.ForestRow;

class ForestFormulaTest {

    /** The example given verbatim in the Structure 2.0 Forest Resource documentation. */
    private static final String DOCUMENTED_EXAMPLE = "10394:0:4/356,10332:0:14707,10374:1:5/240,10348:2:14717";

    @Test
    void parsesTheDocumentedExample() {
        List<ForestRow> rows = ForestFormula.parse(DOCUMENTED_EXAMPLE);

        assertThat(rows).hasSize(4);
        assertThat(rows.get(0)).isEqualTo(new ForestRow(10394, 0, 4, 356L, null, null));
        assertThat(rows.get(1)).isEqualTo(new ForestRow(10332, 0, null, 14707L, null, null));
        assertThat(rows.get(2)).isEqualTo(new ForestRow(10374, 1, 5, 240L, null, null));
        assertThat(rows.get(3)).isEqualTo(new ForestRow(10348, 2, null, 14717L, null, null));
    }

    @Test
    void distinguishesIssueRowsFromOtherItemTypes() {
        List<ForestRow> rows = ForestFormula.parse(DOCUMENTED_EXAMPLE);

        assertThat(rows.get(0).isIssue()).isFalse();
        assertThat(rows.get(1).isIssue()).isTrue();
        assertThat(rows.get(1).issueId()).contains(14707L);
        assertThat(rows.get(0).issueId()).isEmpty();
    }

    @Test
    void parsesAStringItemIdentity() {
        List<ForestRow> rows = ForestFormula.parse("42:0:7//abc-def");

        assertThat(rows).singleElement().isEqualTo(new ForestRow(42, 0, 7, null, "abc-def", null));
    }

    @Test
    void keepsTheOptionalRowSemanticsField() {
        List<ForestRow> rows = ForestFormula.parse("42:0:14707:s");

        assertThat(rows).singleElement().extracting(ForestRow::semantics).isEqualTo("s");
    }

    @Test
    void treatsAnAbsentFormulaAsAnEmptyForest() {
        assertThat(ForestFormula.parse(null)).isEmpty();
        assertThat(ForestFormula.parse("  ")).isEmpty();
    }

    @Test
    void rebuildsTheHierarchyTheDepthsEncode() {
        List<ForestNode> roots = ForestFormula.toTree(ForestFormula.parse(DOCUMENTED_EXAMPLE));

        assertThat(roots).hasSize(2);
        assertThat(roots.get(0).isLeaf()).isTrue();
        ForestNode second = roots.get(1);
        assertThat(second.children()).hasSize(1);
        assertThat(second.children().get(0).children()).hasSize(1);
        assertThat(second.children().get(0).children().get(0).row().rowId()).isEqualTo(10348);
    }

    @Test
    void flattensBackToDocumentOrder() {
        List<ForestRow> rows = ForestFormula.parse(DOCUMENTED_EXAMPLE);

        List<Long> flattened = ForestFormula.toTree(rows).stream()
                .flatMap(ForestNode::flatten)
                .map(node -> node.row().rowId())
                .toList();

        assertThat(flattened).containsExactly(10394L, 10332L, 10374L, 10348L);
    }

    @Test
    void rejectsAFormulaWhoseDepthSkipsALevel() {
        assertThatThrownBy(() -> ForestFormula.toTree(ForestFormula.parse("1:0:100,2:2:200")))
                .isInstanceOf(JiraSerializationException.class)
                .hasMessageContaining("depth 2");
    }

    @Test
    void rejectsAMalformedRow() {
        assertThatThrownBy(() -> ForestFormula.parse("nonsense"))
                .isInstanceOf(JiraSerializationException.class)
                .hasMessageContaining("rowId:depth:item");
    }
}
