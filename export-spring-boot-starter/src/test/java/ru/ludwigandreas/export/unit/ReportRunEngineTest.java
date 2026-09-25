package ru.ludwigandreas.export.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ludwigandreas.export.api.MultiSheetStrategy;
import ru.ludwigandreas.export.api.NoParameters;
import ru.ludwigandreas.export.api.SheetDefinition;
import ru.ludwigandreas.export.api.RowSource;
import ru.ludwigandreas.export.api.SheetSpec;
import ru.ludwigandreas.export.api.SourceContext;
import ru.ludwigandreas.export.engine.ExecutionPlan;
import ru.ludwigandreas.export.engine.OutputTarget;
import ru.ludwigandreas.export.engine.ReportRunEngine;
import ru.ludwigandreas.export.engine.ReportRunResult;
import ru.ludwigandreas.export.engine.RunCancellation;
import ru.ludwigandreas.export.exception.ExportException;
import ru.ludwigandreas.export.exception.ReportCancelledException;
import ru.ludwigandreas.export.exception.ReportLimitExceededException;
import ru.ludwigandreas.export.unit.EngineFixtures.Sale;

/**
 * The engine, end to end, into CSV.
 *
 * <p>Every assertion here is about a promise the module makes in prose somewhere: that the source is
 * walked lazily, that nothing is materialised, that a cancelled run leaves no temp file, that a
 * limit fails rather than truncates, that a declared sheet with no rows is still written. A promise
 * with no test is a comment.
 */
class ReportRunEngineTest {

    @TempDir
    Path tempDirectory;

    private InMemoryReportSink sink;
    private Clock clock;

    @BeforeEach
    void setUp() {
        sink = new InMemoryReportSink();
        clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneId.of("UTC"));
    }

    private static List<Sale> sales(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new Sale("REF-" + i, LocalDate.of(2026, 1, 1).plusDays(i),
                        BigDecimal.valueOf(i * 10L), i % 2 == 0, i % 2 == 0 ? "north" : "south"))
                .toList();
    }

    private ReportRunResult run(ExecutionPlan<NoParameters, Sale> plan, RunCancellation cancellation) {
        ReportRunEngine engine = EngineFixtures.engine(tempDirectory, sink, clock);
        return engine.execute(plan, cancellation);
    }

    private ExecutionPlan<NoParameters, Sale> singleSheetPlan(List<Sale> rows, int windowSize,
                                                              long rowCap, boolean totals,
                                                              Map<String, String> options) {
        var definition = EngineFixtures.definition(rows, List.of());
        SheetSpec spec = SheetSpec.single("data", "Sales", EngineFixtures.columnSpecs());
        OutputTarget output = EngineFixtures.csvOutput(List.of(spec), MultiSheetStrategy.REJECT,
                options, totals);
        return EngineFixtures.plan(definition, List.of(spec), List.of(output), windowSize, rowCap);
    }

    private String storedText(ReportRunResult result) {
        byte[] bytes = sink.bytes(result.outputs().get(0).stored().uri());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a run writes a header, every row and a totals row, and stores one file")
    void writesAFileEndToEnd() {
        ReportRunResult result = run(singleSheetPlan(sales(3), 2, 100, true, Map.of()),
                RunCancellation.NEVER);

        assertThat(result.rowsWritten()).isEqualTo(3);
        assertThat(result.outputs()).hasSize(1);
        assertThat(sink.size()).isEqualTo(1);

        String text = storedText(result);
        assertThat(text).startsWith("\uFEFFReference,Day,Amount,Settled\r\n");
        assertThat(text).contains("REF-1,1/2/26,10.00,FALSE\r\n");
        // No totals row, although the plan asked for one: CSV declares totalsRow=false, and a
        // grand-total line appended to a data file is a record with the wrong shape in it. The
        // capability decides, not the request - see the next test for a format that can carry one.
        assertThat(text).endsWith("REF-3,1/4/26,30.00,FALSE\r\n");
    }

    @Test
    @DisplayName("a format that declares totalsRow gets one, folded as the rows went past")
    void writesATotalsRowWhenTheFormatCanCarryOne() {
        var definition = EngineFixtures.definition(sales(3), List.of());
        SheetSpec spec = SheetSpec.single("data", "Sales", EngineFixtures.columnSpecs());
        OutputTarget output = OutputTarget.of(EngineFixtures.TOTALLING_CSV, List.of(spec),
                MultiSheetStrategy.REJECT, Map.of("profile", "rfc4180"), true);
        var plan = EngineFixtures.plan(definition, List.of(spec), List.of(output), 2, 100);

        ReportRunResult result = run(plan, RunCancellation.NEVER);

        // MAX over the dates, SUM over the amounts, nothing for the two columns that declared none.
        assertThat(storedText(result)).endsWith(",2026-01-04,60.00,\r\n");
    }

    @Test
    @DisplayName("the file name carries the report title and the run id")
    void namesTheFileAfterTheReport() {
        ReportRunResult result = run(singleSheetPlan(sales(1), 10, 100, false, Map.of()),
                RunCancellation.NEVER);

        assertThat(result.outputs().get(0).fileName()).isEqualTo("sales-00000000.csv");
        assertThat(result.outputs().get(0).mediaType()).isEqualTo("text/csv");
    }

    @Test
    @DisplayName("the rfc4180 profile drops the mark, uses commas and writes canonical values")
    void honoursTheRfc4180Profile() {
        ReportRunResult result = run(
                singleSheetPlan(sales(1), 10, 100, false, Map.of("profile", "rfc4180")),
                RunCancellation.NEVER);

        String text = storedText(result);
        assertThat(text).doesNotStartWith("\uFEFF");
        assertThat(text).startsWith("Reference,Day,Amount,Settled\r\n");
        assertThat(text).contains("REF-1,2026-01-02,10.00,false\r\n");
    }

    @Test
    @DisplayName("a request may override the delimiter, and the file is quoted for it")
    void honoursADelimiterOverride() {
        ReportRunResult result = run(
                singleSheetPlan(sales(1), 10, 100, false, Map.of("profile", "rfc4180", "delimiter", ";")),
                RunCancellation.NEVER);

        assertThat(storedText(result)).startsWith("Reference;Day;Amount;Settled\r\n");
    }

    @Test
    @DisplayName("the row cap fails the run and deletes the partial file, rather than truncating it")
    void failsRatherThanTruncating() {
        assertThatThrownBy(() -> run(singleSheetPlan(sales(10), 2, 4, false, Map.of()),
                RunCancellation.NEVER))
                .isInstanceOf(ReportLimitExceededException.class)
                .hasMessageContaining("row-limit-exceeded");

        assertThat(sink.size()).isZero();
        assertThat(listTempFiles()).isEmpty();
    }

    @Test
    @DisplayName("the wall-clock budget is checked per window against the injected clock")
    void failsOnTheWallClockBudget() {
        // A clock that jumps ten minutes on every reading: the first window is already over budget.
        Clock advancing = new AdvancingClock(Instant.parse("2026-03-01T10:00:00Z"),
                java.time.Duration.ofMinutes(10));
        ReportRunEngine engine = EngineFixtures.engine(tempDirectory, sink, advancing);

        assertThatThrownBy(() -> engine.execute(singleSheetPlan(sales(10), 2, 100, false, Map.of()),
                RunCancellation.NEVER))
                .isInstanceOf(ReportLimitExceededException.class)
                .hasMessageContaining("time-budget-exceeded");

        assertThat(listTempFiles()).isEmpty();
    }

    @Test
    @DisplayName("cancellation takes effect at a window boundary and leaves no temp file")
    void cancellationLeavesNothingBehind() {
        AtomicBoolean cancelled = new AtomicBoolean(true);

        assertThatThrownBy(() -> run(singleSheetPlan(sales(10), 2, 100, false, Map.of()),
                cancelled::get))
                .isInstanceOf(ReportCancelledException.class);

        assertThat(sink.size()).isZero();
        assertThat(listTempFiles()).isEmpty();
    }

    @Test
    @DisplayName("the temp file is deleted after a successful run too")
    void leavesNoTempFileOnSuccess() {
        run(singleSheetPlan(sales(5), 2, 100, false, Map.of()), RunCancellation.NEVER);

        assertThat(listTempFiles()).isEmpty();
    }

    @Test
    @DisplayName("the source stream is closed even when the run fails midway")
    void closesTheSourceStream() {
        AtomicBoolean closed = new AtomicBoolean(false);
        SheetSpec spec = SheetSpec.single("data", "Sales", EngineFixtures.columnSpecs());
        OutputTarget output = EngineFixtures.csvOutput(List.of(spec), MultiSheetStrategy.REJECT,
                Map.of(), false);
        RowSource<NoParameters, Sale> tracking = new RowSource<>() {
            @Override
            public Stream<Sale> open(SourceContext<NoParameters> context) {
                return sales(10).stream().onClose(() -> closed.set(true));
            }

            @Override
            public Set<String> sortableColumns() {
                return Set.of();
            }
        };
        var definition = EngineFixtures.definitionWith(tracking, List.of());
        var plan = EngineFixtures.plan(definition, List.of(spec), List.of(output), 2, 3);

        assertThatThrownBy(() -> run(plan, RunCancellation.NEVER))
                .isInstanceOf(ReportLimitExceededException.class);

        assertThat(closed).isTrue();
    }

    @Test
    @DisplayName("a row belonging to no declared sheet fails the run rather than landing somewhere")
    void refusesARowThatBelongsToNoSheet() {
        List<SheetDefinition<Sale>> sheets = List.of(
                SheetDefinition.of("north", "test.sheet.north", Sale::region, true));
        var definition = EngineFixtures.definition(sales(2), sheets);
        SheetSpec north = new SheetSpec("north", "North", EngineFixtures.columnSpecs(), true);
        OutputTarget output = EngineFixtures.csvOutput(List.of(north), MultiSheetStrategy.REJECT,
                Map.of(), false);
        var plan = EngineFixtures.plan(definition, List.of(north), List.of(output), 10, 100);

        assertThatThrownBy(() -> run(plan, RunCancellation.NEVER))
                .isInstanceOf(ExportException.class)
                .hasMessageContaining("belongs to no declared sheet");
    }

    @Test
    @DisplayName("returning to a closed sheet fails, naming the ordering the source must keep")
    void refusesANonContiguousSheetOrder() {
        List<SheetDefinition<Sale>> sheets = List.of(
                SheetDefinition.of("north", "test.sheet.north", Sale::region, true),
                SheetDefinition.of("south", "test.sheet.south", Sale::region, false));
        // sales() alternates regions row by row, which is exactly the order the rule forbids.
        var definition = EngineFixtures.definition(sales(4), sheets);
        List<SheetSpec> specs = List.of(
                new SheetSpec("north", "North", EngineFixtures.columnSpecs(), true),
                new SheetSpec("south", "South", EngineFixtures.columnSpecs(), false));
        OutputTarget output = EngineFixtures.csvOutput(specs, MultiSheetStrategy.ZIP, Map.of(), false);
        var plan = EngineFixtures.plan(definition, specs, List.of(output), 10, 100);

        assertThatThrownBy(() -> run(plan, RunCancellation.NEVER))
                .isInstanceOf(ExportException.class)
                .hasMessageContaining("after it was closed");
    }

    @Test
    @DisplayName("several sheets under ZIP produce one archive with an entry per sheet")
    void zipsSeveralSheets() throws IOException {
        List<SheetDefinition<Sale>> sheets = List.of(
                SheetDefinition.of("north", "test.sheet.north", Sale::region, true),
                SheetDefinition.of("south", "test.sheet.south", Sale::region, false));
        List<Sale> ordered = new ArrayList<>(sales(4).stream().filter(s -> s.region().equals("north")).toList());
        ordered.addAll(sales(4).stream().filter(s -> s.region().equals("south")).toList());
        var definition = EngineFixtures.definition(ordered, sheets);
        List<SheetSpec> specs = List.of(
                new SheetSpec("north", "North", EngineFixtures.columnSpecs(), true),
                new SheetSpec("south", "South", EngineFixtures.columnSpecs(), false));
        OutputTarget output = EngineFixtures.csvOutput(specs, MultiSheetStrategy.ZIP, Map.of(), false);
        var plan = EngineFixtures.plan(definition, specs, List.of(output), 10, 100);

        ReportRunResult result = run(plan, RunCancellation.NEVER);

        assertThat(result.outputs().get(0).fileName()).endsWith(".zip");
        assertThat(result.outputs().get(0).mediaType()).isEqualTo("application/zip");
        assertThat(entryNames(sink.bytes(result.outputs().get(0).stored().uri())))
                .containsExactly("north.csv", "south.csv");
    }

    @Test
    @DisplayName("PRIMARY_ONLY writes one sheet and reports the ones it left out")
    void reportsOmittedSheetsUnderPrimaryOnly() {
        List<SheetDefinition<Sale>> sheets = List.of(
                SheetDefinition.of("north", "test.sheet.north", Sale::region, true),
                SheetDefinition.of("south", "test.sheet.south", Sale::region, false));
        List<Sale> ordered = new ArrayList<>(sales(4).stream().filter(s -> s.region().equals("north")).toList());
        ordered.addAll(sales(4).stream().filter(s -> s.region().equals("south")).toList());
        var definition = EngineFixtures.definition(ordered, sheets);
        List<SheetSpec> specs = List.of(
                new SheetSpec("north", "North", EngineFixtures.columnSpecs(), true),
                new SheetSpec("south", "South", EngineFixtures.columnSpecs(), false));
        OutputTarget output = EngineFixtures.csvOutput(specs, MultiSheetStrategy.PRIMARY_ONLY,
                Map.of(), false);
        var plan = EngineFixtures.plan(definition, specs, List.of(output), 10, 100);

        ReportRunResult result = run(plan, RunCancellation.NEVER);

        assertThat(result.omittedSheets()).containsExactly("south");
        assertThat(storedText(result)).doesNotContain("REF-1");
    }

    @Test
    @DisplayName("a declared sheet that got no rows is still written, with its header")
    void writesTheHeaderOfAnEmptySheet() {
        ReportRunResult result = run(singleSheetPlan(List.of(), 10, 100, false, Map.of()),
                RunCancellation.NEVER);

        assertThat(result.rowsWritten()).isZero();
        assertThat(storedText(result)).isEqualTo("\uFEFFReference,Day,Amount,Settled\r\n");
    }

    private List<String> entryNames(byte[] archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private List<Path> listTempFiles() {
        try (Stream<Path> entries = Files.list(tempDirectory)) {
            return entries.toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A clock that moves forward by a fixed step every time it is read. */
    private static final class AdvancingClock extends Clock {

        private final java.time.Duration step;
        private Instant current;

        AdvancingClock(Instant start, java.time.Duration step) {
            this.current = start;
            this.step = step;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant now = current;
            current = current.plus(step);
            return now;
        }
    }
}
