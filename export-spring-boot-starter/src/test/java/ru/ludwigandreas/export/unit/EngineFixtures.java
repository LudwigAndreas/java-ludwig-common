package ru.ludwigandreas.export.unit;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;
import ru.ludwigandreas.export.api.Aggregate;
import ru.ludwigandreas.export.api.CellFormat;
import ru.ludwigandreas.export.api.Column;
import ru.ludwigandreas.export.api.ColumnSpec;
import ru.ludwigandreas.export.api.FormatCapabilities;
import ru.ludwigandreas.export.api.MultiSheetStrategy;
import ru.ludwigandreas.export.api.NoParameters;
import ru.ludwigandreas.export.api.RenderContext;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.ReportWriter;
import ru.ludwigandreas.export.api.ReportWriterFactory;
import ru.ludwigandreas.export.api.RowSource;
import ru.ludwigandreas.export.api.SheetDefinition;
import ru.ludwigandreas.export.api.SheetSpec;
import ru.ludwigandreas.export.api.SourceContext;
import ru.ludwigandreas.export.api.StandardReportFormats;
import ru.ludwigandreas.export.api.WriterContext;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.engine.ExecutionPlan;
import ru.ludwigandreas.export.enrich.EnrichmentExecutor;
import ru.ludwigandreas.export.engine.OutputTarget;
import ru.ludwigandreas.export.engine.ReportRunEngine;
import ru.ludwigandreas.export.engine.TempFiles;
import ru.ludwigandreas.export.format.csv.CsvProfile;
import ru.ludwigandreas.export.format.csv.CsvReportWriter;
import ru.ludwigandreas.export.format.csv.CsvReportWriterFactory;
import ru.ludwigandreas.export.i18n.ExportMessages;
import ru.ludwigandreas.export.metrics.NoopExportMetrics;

/**
 * The report the engine tests run, and the wiring to run it with.
 *
 * <p>One fixture rather than a builder per test: these tests are about what the engine does with a
 * given plan, and a configurable fixture would put the interesting variation in the fixture instead
 * of in the test that names it.
 */
final class EngineFixtures {

    /** A row with one of each cell shape the CSV writer has to render. */
    record Sale(String reference, LocalDate day, BigDecimal amount, boolean settled, String region) {
    }

    /** Resolves a key to the key itself, except for the few the fixtures assert on. */
    static final ExportMessages MESSAGES = (key, locale, args) -> switch (key) {
        case "test.report.sales" -> "Sales";
        case "test.column.reference" -> "Reference";
        case "test.column.day" -> "Day";
        case "test.column.amount" -> "Amount";
        case "test.column.settled" -> "Settled";
        case "test.sheet.north" -> "North";
        case "test.sheet.south" -> "South";
        case "ludwig.export.cell.placeholder" -> "-";
        case "ludwig.export.cell.unavailable" -> "n/a";
        case "ludwig.export.totals.label" -> "Total";
        case "ludwig.export.metadata.sheet-title" -> "About this report";
        case "ludwig.export.metadata.continued" -> "continued";
        default -> key;
    };

    static final RenderContext EN = new RenderContext(Locale.ENGLISH, ZoneId.of("UTC"));

    /**
     * A format that is CSV in every respect except that it declares it can carry a totals row.
     *
     * <p>Exists because the shipped CSV format declares {@code totalsRow=false} on purpose, so the
     * totals path has no shipped format to be exercised through until the XLSX writer lands. It is
     * also the module's own use of the format seam, which makes it a test of that seam as much as of
     * the totals: registering a new {@link ReportFormat} and a factory for it is all it takes.
     */
    static final ReportFormat TOTALLING_CSV = new ReportFormat() {

        @Override
        public String id() {
            return "csv-totals";
        }

        @Override
        public String mediaType() {
            return "text/csv";
        }

        @Override
        public String fileExtension() {
            return "csv";
        }

        @Override
        public FormatCapabilities capabilities() {
            return new FormatCapabilities(false, false, true, false, false,
                    FormatCapabilities.UNLIMITED_ROWS);
        }
    };

    private EngineFixtures() {
    }

    static List<Column<Sale, ?>> columns() {
        return List.of(
                Column.<Sale, String>of("reference", String.class)
                        .extractor(Sale::reference)
                        .headerKey("test.column.reference")
                        .format(CellFormat.text())
                        .build(),
                Column.<Sale, LocalDate>of("day", LocalDate.class)
                        .extractor(Sale::day)
                        .headerKey("test.column.day")
                        .format(CellFormat.date())
                        .aggregate(Aggregate.MAX)
                        .build(),
                Column.<Sale, BigDecimal>of("amount", BigDecimal.class)
                        .extractor(Sale::amount)
                        .headerKey("test.column.amount")
                        .format(CellFormat.number(2))
                        .aggregate(Aggregate.SUM)
                        .build(),
                Column.<Sale, Boolean>of("settled", Boolean.class)
                        .extractor(Sale::settled)
                        .headerKey("test.column.settled")
                        .format(CellFormat.bool())
                        .build());
    }

    static List<ColumnSpec> columnSpecs() {
        return List.of(
                new ColumnSpec("reference", "Reference", CellFormat.text(), 20, Aggregate.NONE, false),
                new ColumnSpec("day", "Day", CellFormat.date(), 12, Aggregate.MAX, false),
                new ColumnSpec("amount", "Amount", CellFormat.number(2), 12, Aggregate.SUM, false),
                new ColumnSpec("settled", "Settled", CellFormat.bool(), 10, Aggregate.NONE, false));
    }

    static ReportDefinition<NoParameters, Sale> definition(List<Sale> rows,
                                                           List<SheetDefinition<Sale>> sheets) {
        return definitionWith(new FixedSource(rows), sheets);
    }

    static ReportDefinition<NoParameters, Sale> definitionWith(RowSource<NoParameters, Sale> source,
                                                               List<SheetDefinition<Sale>> sheets) {
        return builderWith(source, sheets).build();
    }

    /** The builder behind {@link #definition}, for a test that wants to add one more thing. */
    static ReportDefinition.ReportDefinitionBuilder<NoParameters, Sale> definitionBuilder(
            List<Sale> rows, List<SheetDefinition<Sale>> sheets) {
        return builderWith(new FixedSource(rows), sheets);
    }

    private static ReportDefinition.ReportDefinitionBuilder<NoParameters, Sale> builderWith(
            RowSource<NoParameters, Sale> source, List<SheetDefinition<Sale>> sheets) {
        var builder = ReportDefinition.<NoParameters, Sale>of("catalog.sales", NoParameters.class, Sale.class)
                .titleKey("test.report.sales")
                .source(source)
                .allowedFormats(Set.of(StandardReportFormats.CSV))
                .defaultFormat(StandardReportFormats.CSV);
        columns().forEach(builder::column);
        sheets.forEach(builder::sheet);
        return builder;
    }

    /** The shipped CSV factory, for a planner test that needs a real one. */
    static ReportWriterFactory csvFactory() {
        return new CsvReportWriterFactory(new ExportProperties().getFormats().getCsv(), MESSAGES);
    }

    /**
     * A factory claiming the XLSX format without carrying POI.
     *
     * <p>The planner only ever asks a factory for its format and its option names, so a stub is
     * enough - and it keeps a test about request validation from depending on a workbook library.
     */
    static ReportWriterFactory xlsxStubFactory() {
        return new ReportWriterFactory() {
            @Override
            public ReportFormat format() {
                return StandardReportFormats.XLSX;
            }

            @Override
            public ReportWriter create(WriterContext context) {
                throw new UnsupportedOperationException("a planner test never writes a workbook");
            }
        };
    }

    static ExecutionPlan<NoParameters, Sale> plan(ReportDefinition<NoParameters, Sale> definition,
                                                  List<SheetSpec> specs, List<OutputTarget> outputs,
                                                  int windowSize, long rowCap) {
        return plan(definition, specs, outputs, windowSize, rowCap, 2);
    }

    static ExecutionPlan<NoParameters, Sale> plan(ReportDefinition<NoParameters, Sale> definition,
                                                  List<SheetSpec> specs, List<OutputTarget> outputs,
                                                  int windowSize, long rowCap, int handoffDepth) {
        return ExecutionPlan.<NoParameters, Sale>builder()
                .runId(UUID.fromString("00000000-0000-4000-8000-000000000001"))
                .definition(definition)
                .parameters(NoParameters.INSTANCE)
                .columns(columns())
                .sheetDefinitions(definition.getSheets())
                .sheetSpecs(specs)
                .outputs(outputs)
                .render(EN)
                .principalId("tester")
                .authorities(Set.of())
                .correlationId("corr-1")
                .pageSize(windowSize)
                .windowSize(windowSize)
                .handoffQueueDepth(handoffDepth)
                .enrichmentCacheSize(1_000)
                .rowCap(rowCap)
                .wallClockBudget(java.time.Duration.ofMinutes(5))
                .metadata(List.of())
                .build();
    }

    static OutputTarget csvOutput(List<SheetSpec> sheets, MultiSheetStrategy strategy,
                                  Map<String, String> options, boolean totals) {
        return OutputTarget.of(StandardReportFormats.CSV, sheets, strategy, options, totals);
    }

    static ReportRunEngine engine(Path tempDirectory, InMemoryReportSink sink, Clock clock) {
        return engine(tempDirectory, sink, clock, null);
    }

    /**
     * An engine wired for a test.
     *
     * <p>Both executors are left null by default, so the pipeline runs on the calling thread: the
     * engine tests are about what ends up in the file, and a scheduler between the halves would make
     * every one of them a concurrency test as well. The pump test passes a real pool, which is where
     * the hand-off actually gets exercised.
     */
    static ReportRunEngine engine(Path tempDirectory, InMemoryReportSink sink, Clock clock,
                                  ExecutorService prefetch) {
        ExportProperties properties = new ExportProperties();
        ReportWriterFactory csv = new CsvReportWriterFactory(properties.getFormats().getCsv(), MESSAGES);
        ReportWriterFactory totalling = new TotallingCsvFactory(properties.getFormats().getCsv());
        EnrichmentExecutor enrichment = new EnrichmentExecutor(null, properties.getEnrichment(),
                NoopExportMetrics.INSTANCE, MESSAGES);
        return new ReportRunEngine(List.of(csv, totalling), sink, new TempFiles(tempDirectory, clock),
                MESSAGES, clock, enrichment, NoopExportMetrics.INSTANCE, prefetch);
    }

    /** The factory behind {@link #TOTALLING_CSV}; the whole extension point, in one class. */
    record TotallingCsvFactory(ExportProperties.Csv settings) implements ReportWriterFactory {

        @Override
        public ReportFormat format() {
            return TOTALLING_CSV;
        }

        @Override
        public Set<String> supportedOptions() {
            return CsvProfile.OPTIONS;
        }

        @Override
        public ReportWriter create(WriterContext context) throws IOException {
            CsvProfile profile = CsvProfile.resolve(context.options(), settings.getProfile(),
                    settings.isAllowRequestOverride(), context.render().locale());
            return new CsvReportWriter(context, profile, MESSAGES);
        }
    }

    /** A source that yields a fixed list, lazily, and declares one sortable column. */
    record FixedSource(List<Sale> rows) implements RowSource<NoParameters, Sale> {

        @Override
        public Stream<Sale> open(SourceContext<NoParameters> context) {
            return rows.stream();
        }

        @Override
        public Set<String> sortableColumns() {
            return Set.of();
        }
    }
}
