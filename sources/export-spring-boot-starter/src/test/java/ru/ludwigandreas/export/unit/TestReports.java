package ru.ludwigandreas.export.unit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import ru.ludwigandreas.export.api.Aggregate;
import ru.ludwigandreas.export.api.CellFormat;
import ru.ludwigandreas.export.api.Column;
import ru.ludwigandreas.export.api.NoParameters;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.api.RowSource;
import ru.ludwigandreas.export.api.SourceContext;
import ru.ludwigandreas.export.api.StandardReportFormats;

/**
 * Fixtures the definition and registry tests build on.
 *
 * <p>Kept deliberately small and literal rather than parameterised: a test fixture that is itself
 * configurable ends up asserting the fixture's behaviour, and these tests exist to pin down what a
 * <em>wrong</em> definition does.
 */
final class TestReports {

    /** A row with one of each shape a column can read. */
    record OrderRow(String number, LocalDate placedOn, BigDecimal total, String customerId) {
    }

    /** A source that yields a fixed list and declares one sortable column. */
    static final class FixedSource implements RowSource<NoParameters, OrderRow> {

        private final List<OrderRow> rows;
        private final Set<String> sortable;

        FixedSource(List<OrderRow> rows, Set<String> sortable) {
            this.rows = List.copyOf(rows);
            this.sortable = Set.copyOf(sortable);
        }

        @Override
        public Stream<OrderRow> open(SourceContext<NoParameters> context) {
            return rows.stream();
        }

        @Override
        public Set<String> sortableColumns() {
            return sortable;
        }
    }

    static Column<OrderRow, String> numberColumn() {
        return Column.<OrderRow, String>of("number", String.class)
                .extractor(OrderRow::number)
                .headerKey("test.column.number")
                .format(CellFormat.text())
                .build();
    }

    static Column<OrderRow, BigDecimal> totalColumn() {
        return Column.<OrderRow, BigDecimal>of("total", BigDecimal.class)
                .extractor(OrderRow::total)
                .headerKey("test.column.total")
                .format(CellFormat.number(2))
                .aggregate(Aggregate.SUM)
                .build();
    }

    static ReportDefinition.ReportDefinitionBuilder<NoParameters, OrderRow> orders() {
        return ReportDefinition.<NoParameters, OrderRow>of("catalog.orders", NoParameters.class, OrderRow.class)
                .titleKey("test.report.orders")
                .source(new FixedSource(List.of(), Set.of("number")))
                .column(numberColumn())
                .column(totalColumn())
                .allowedFormats(Set.of(StandardReportFormats.XLSX, StandardReportFormats.CSV))
                .defaultFormat(StandardReportFormats.XLSX);
    }

    private TestReports() {
    }
}
