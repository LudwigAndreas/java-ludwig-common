package ru.ludwigandreas.export.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.ludwigandreas.export.api.CellFormat;
import ru.ludwigandreas.export.api.Column;
import ru.ludwigandreas.export.api.NoParameters;
import ru.ludwigandreas.export.api.NullPolicy;
import ru.ludwigandreas.export.api.StandardReportFormats;
import ru.ludwigandreas.export.unit.TestReports.OrderRow;

/**
 * What a definition refuses to be built as.
 *
 * <p>Every case here is one the constructor catches on its own, without seeing the rest of the
 * estate; the checks that need the estate belong to {@link ReportDefinitionRegistryTest}.
 */
class ReportDefinitionTest {

    @ParameterizedTest
    @ValueSource(strings = {"Catalog.Orders", "catalog orders", "catalog_orders", "1catalog", "catalog."})
    @DisplayName("a key that is not lowercase dot-separated segments is refused")
    void rejectsMalformedKeys(String key) {
        assertThatThrownBy(() -> TestReports.orders().key(key).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(key);
    }

    @Test
    @DisplayName("a default format outside the allowed set is refused")
    void rejectsDefaultFormatOutsideAllowedSet() {
        assertThatThrownBy(() -> TestReports.orders()
                .allowedFormats(Set.of(StandardReportFormats.CSV))
                .defaultFormat(StandardReportFormats.XLSX)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("xlsx");
    }

    @Test
    @DisplayName("the first allowed format becomes the default when none is declared")
    void defaultsToTheOnlyAllowedFormat() {
        var definition = TestReports.orders()
                .allowedFormats(Set.of(StandardReportFormats.CSV))
                .defaultFormat(null)
                .build();

        assertThat(definition.getDefaultFormat()).isEqualTo(StandardReportFormats.CSV);
    }

    @Test
    @DisplayName("two columns with the same id are refused, naming the id")
    void rejectsDuplicateColumnIds() {
        assertThatThrownBy(() -> TestReports.orders().column(TestReports.numberColumn()).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("column number");
    }

    @Test
    @DisplayName("a report with no columns is refused")
    void rejectsEmptyColumnList() {
        assertThatThrownBy(() -> TestReports.orders().clearColumns().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("columns");
    }

    @Test
    @DisplayName("a row cap below one is refused")
    void rejectsNonPositiveMaxRows() {
        assertThatThrownBy(() -> TestReports.orders().maxRows(0L).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRows");
    }

    @Test
    @DisplayName("a single-sheet definition gets one sheet without declaring it")
    void suppliesTheImplicitSheet() {
        var definition = TestReports.orders().build();

        assertThat(definition.getSheets()).hasSize(1);
        assertThat(definition.isMultiSheet()).isFalse();
        assertThat(definition.getSheets().get(0).titleKey()).isEqualTo("test.report.orders");
    }

    @Test
    @DisplayName("NullPolicy.ZERO on a text column is refused, because zero is not a missing string")
    void rejectsZeroNullPolicyOnTextColumn() {
        assertThatThrownBy(() -> Column.<OrderRow, String>of("note", String.class)
                .extractor(OrderRow::number)
                .headerKey("test.column.note")
                .format(CellFormat.text())
                .nullPolicy(NullPolicy.ZERO)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TEXT");
    }

    @Test
    @DisplayName("column visibility is a disjunction over the requester's authorities")
    void columnVisibilityIsADisjunction() {
        Column<OrderRow, BigDecimal> restricted = Column.<OrderRow, BigDecimal>of("margin", BigDecimal.class)
                .extractor(OrderRow::total)
                .headerKey("test.column.margin")
                .visibleFor(Set.of("ROLE_FINANCE", "ROLE_AUDIT"))
                .build();

        assertThat(restricted.isVisibleTo(Set.of("ROLE_AUDIT"))).isTrue();
        assertThat(restricted.isVisibleTo(Set.of("ROLE_SALES"))).isFalse();
        assertThat(restricted.isVisibleTo(Set.of())).isFalse();
        assertThat(TestReports.numberColumn().isVisibleTo(Set.of())).isTrue();
    }

    @Test
    @DisplayName("visibleColumns removes what the requester may not see, rather than blanking it")
    void visibleColumnsRemovesRatherThanBlanks() {
        Column<OrderRow, BigDecimal> restricted = Column.<OrderRow, BigDecimal>of("margin", BigDecimal.class)
                .extractor(OrderRow::total)
                .headerKey("test.column.margin")
                .visibleFor(Set.of("ROLE_FINANCE"))
                .build();
        var definition = TestReports.orders().column(restricted).build();

        assertThat(definition.visibleColumns(Set.of("ROLE_SALES")))
                .extracting(Column::getId)
                .containsExactly("number", "total");
        assertThat(definition.visibleColumns(Set.of("ROLE_FINANCE")))
                .extracting(Column::getId)
                .containsExactly("number", "total", "margin");
    }

    @Test
    @DisplayName("a definition with required authorities is not runnable without one of them")
    void requiredAuthoritiesGateTheWholeReport() {
        var definition = TestReports.orders().requiredAuthorities(Set.of("ROLE_REPORTS")).build();

        assertThat(definition.isRunnableBy(Set.of("ROLE_REPORTS"))).isTrue();
        assertThat(definition.isRunnableBy(Set.of("ROLE_SALES"))).isFalse();
        assertThat(definition.isRunnableBy(null)).isFalse();
    }

    @Test
    @DisplayName("toString names the report without exposing a parameter value")
    void toStringIsSafeForLogs() {
        var definition = TestReports.orders().build();

        assertThat(definition.toString())
                .contains("catalog.orders")
                .contains("2 columns");
    }

    @Test
    @DisplayName("the parameter type of a report that takes none is NoParameters")
    void reportWithoutParametersUsesNoParameters() {
        assertThat(TestReports.orders().build().getParameterType()).isEqualTo(NoParameters.class);
        assertThat(TestReports.orders().build().columnIds()).isEqualTo(List.of("number", "total"));
    }
}
