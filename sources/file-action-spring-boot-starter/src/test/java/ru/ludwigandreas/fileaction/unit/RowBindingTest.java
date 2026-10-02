package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.fileaction.api.ColumnBinding;
import ru.ludwigandreas.fileaction.api.RowBinding;

/** What a {@link RowBinding} accepts, what it refuses, and when it refuses it. */
class RowBindingTest {

    @Test
    @DisplayName("columns are declared in order and required by default")
    void declaresColumnsInOrder() {
        RowBinding<OrderLine> binding = RowBinding.of(OrderLine.class)
                .sheet("Orders")
                .column("sku", "SKU")
                .column("quantity", "Qty")
                .column("comment", "Comment").optional()
                .build();

        assertThat(binding.sheet()).isEqualTo("Orders");
        assertThat(binding.columns()).extracting(ColumnBinding::field)
                .containsExactly("sku", "quantity", "comment");
        assertThat(binding.requiredColumns()).extracting(ColumnBinding::field)
                .containsExactly("sku", "quantity");
    }

    @Test
    @DisplayName("a header matches ignoring case, surrounding and repeated whitespace, and a no-break space")
    void matchesHeaderLoosely() {
        RowBinding<OrderLine> binding = RowBinding.of(OrderLine.class)
                .column("sku", "Article Code")
                .build();

        assertThat(binding.columnForHeader("Article Code")).isPresent();
        assertThat(binding.columnForHeader("  article   code ")).isPresent();
        assertThat(binding.columnForHeader("ARTICLE" + (char) 0x00A0 + "CODE")).isPresent();
        assertThat(binding.columnForHeader("Article-Code")).isEmpty();
    }

    @Test
    @DisplayName("an alias binds to the same column as the canonical header")
    void aliasesBindToTheSameColumn() {
        RowBinding<OrderLine> binding = RowBinding.of(OrderLine.class)
                .column("sku", "SKU").aliases("Article", "Code")
                .build();

        assertThat(binding.columnForHeader("Article")).contains(binding.columns().get(0));
        assertThat(binding.columnForHeader("Code")).contains(binding.columns().get(0));
    }

    @Test
    @DisplayName("a header the binding does not declare is not an error - a user's sheet has other columns")
    void unknownHeaderIsNotAnError() {
        RowBinding<OrderLine> binding = RowBinding.of(OrderLine.class)
                .column("sku", "SKU")
                .build();

        assertThat(binding.columnForHeader("Warehouse note")).isEmpty();
    }

    @Test
    @DisplayName("a field the row record does not have is refused when the constant is built, not at first upload")
    void refusesUnknownField() {
        assertThatThrownBy(() -> RowBinding.of(OrderLine.class)
                .column("artcile", "SKU")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("artcile")
                .hasMessageContaining("does not have");
    }

    @Test
    @DisplayName("a non-record row type is refused, because the binding reads its target types from the components")
    void refusesNonRecordRowType() {
        assertThatThrownBy(() -> RowBinding.of(String.class).column("value", "Value").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a record");
    }

    @Test
    @DisplayName("two columns matching one header is refused as ambiguous rather than resolved arbitrarily")
    void refusesAmbiguousHeaders() {
        assertThatThrownBy(() -> RowBinding.of(OrderLine.class)
                .column("sku", "Code")
                .column("comment", "Note").aliases("code")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    @DisplayName("one field bound by two columns is refused")
    void refusesDuplicateField() {
        assertThatThrownBy(() -> RowBinding.of(OrderLine.class)
                .column("sku", "SKU")
                .column("sku", "Article")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two columns");
    }

    @Test
    @DisplayName("a binding with no columns is refused")
    void refusesEmptyBinding() {
        assertThatThrownBy(() -> RowBinding.of(OrderLine.class).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declares no columns");
    }

    @Test
    @DisplayName("aliases() before any column is a programming error, reported as one")
    void refusesAliasesWithNoColumn() {
        assertThatThrownBy(() -> RowBinding.of(OrderLine.class).aliases("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no column has been declared");
    }
}
