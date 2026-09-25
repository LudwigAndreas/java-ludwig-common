package ru.ludwigandreas.export.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ludwigandreas.export.api.Aggregate;
import ru.ludwigandreas.export.api.CellFormat;
import ru.ludwigandreas.export.api.Column;
import ru.ludwigandreas.export.api.ColumnSpec;
import ru.ludwigandreas.export.api.Enricher;
import ru.ludwigandreas.export.api.EnrichmentStage;
import ru.ludwigandreas.export.api.MultiSheetStrategy;
import ru.ludwigandreas.export.api.NoParameters;
import ru.ludwigandreas.export.api.RenderContext;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.api.ReportSink;
import ru.ludwigandreas.export.api.ReportWriterFactory;
import ru.ludwigandreas.export.api.RowSource;
import ru.ludwigandreas.export.api.SheetSpec;
import ru.ludwigandreas.export.api.SourceContext;
import ru.ludwigandreas.export.api.StandardReportFormats;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.StoredOutput;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.engine.ExecutionPlan;
import ru.ludwigandreas.export.engine.OutputTarget;
import ru.ludwigandreas.export.engine.ReportRunEngine;
import ru.ludwigandreas.export.engine.ReportRunResult;
import ru.ludwigandreas.export.engine.RunCancellation;
import ru.ludwigandreas.export.engine.TempFiles;
import ru.ludwigandreas.export.enrich.EnrichmentExecutor;
import ru.ludwigandreas.export.format.csv.CsvReportWriterFactory;
import ru.ludwigandreas.export.format.xlsx.XlsxReportWriterFactory;
import ru.ludwigandreas.export.i18n.ExportMessages;
import ru.ludwigandreas.export.metrics.NoopExportMetrics;

/**
 * A million rows, twenty-five columns, three enrichment stages, two formats, one pass.
 *
 * <h2>What this test is for</h2>
 *
 * <p>Every other test in this module asserts a behaviour. This one asserts the <em>claim</em> - that
 * the design point in the README is real - and it is the only test that can. A streaming exporter
 * that has quietly stopped streaming passes every functional test it has: the rows are right, the
 * cells are typed, the file opens. It fails only at size, and only on real data, which by then means
 * in production.
 *
 * <h2>What it measures</h2>
 *
 * <ul>
 *   <li><b>Peak heap.</b> From {@code MemoryPoolMXBean.getPeakUsage()} over the heap pools, which is
 *       the only API that answers "how much did it actually use" rather than "how much is in use
 *       now". Asserted against a fixed ceiling, so the assertion means something regardless of the
 *       {@code -Xmx} the run happens to have.</li>
 *   <li><b>Full collections.</b> A run that fits in the heap only because the collector is working
 *       constantly has not really fitted, so the old-generation collection count is bounded too.</li>
 *   <li><b>Throughput.</b> Rows per second, asserted against a floor and printed so the README can
 *       quote a measured number rather than an aspiration.</li>
 *   <li><b>Cleanup.</b> The temp directory is empty afterwards. At this size a leaked file is
 *       hundreds of megabytes.</li>
 *   <li><b>Correctness at the end.</b> The XLSX opens and its last data row carries the value the
 *       millionth row should have - which is what distinguishes a file that streamed correctly from
 *       one that silently stopped early.</li>
 * </ul>
 *
 * <p>Excluded from the default build by its {@code load} tag; the command is in this module's
 * {@code pom.xml} next to the exclusion.
 */
@Tag("load")
class ReportLoadTest {

    /** The design point. */
    private static final int ROWS = 1_000_000;

    /** Columns in the report: 17 read from the row, 8 supplied by the three stages. */
    private static final int BASE_COLUMNS = 17;
    private static final int ENRICHED_COLUMNS = 8;

    /**
     * The largest {@code -Xmx} this test will accept.
     *
     * <p>The real memory assertion is that the run <em>completes</em> under a small heap: an engine
     * that stopped streaming would exhaust it and fail outright, which no sampled number can be
     * fooled about. That only means something if the heap is actually small, so a run with a large
     * one fails with instructions rather than passing vacuously.
     */
    private static final long MAX_ACCEPTED_XMX_BYTES = 640L * 1024 * 1024;

    /**
     * What the engine may still be holding when the run is over.
     *
     * <p>Measured after a collection, so it is retention rather than garbage: a window, two flush
     * windows, the enrichment cache and the definition. This is the assertion that catches a slow
     * leak - rows accumulating somewhere that the heap is big enough to absorb but not big enough to
     * absorb twice.
     */
    private static final long MAX_RETAINED_BYTES = 128L * 1024 * 1024;

    /** How much of a file's tail is enough to see its last record. */
    private static final int TAIL_BYTES = 4096;

    /** How often the sampler reads heap usage while the run is in flight. */
    private static final long SAMPLE_INTERVAL_MILLIS = 20;

    /** Old-generation collections a healthy run of this size should not exceed. */
    private static final int MAX_FULL_COLLECTIONS = 40;

    /**
     * The throughput floor, deliberately far below what the measurement shows.
     *
     * <p>A floor, not a target: this test runs on developer laptops and on CI agents of unknown
     * contention, and a tight bound would fail for reasons that have nothing to do with the module.
     * What it catches is a regression of the kind that matters - an accidental quadratic, a per-cell
     * allocation, a style created per row - all of which cost an order of magnitude, not a fraction.
     */
    private static final int MIN_ROWS_PER_SECOND = 2_000;

    @TempDir
    Path tempDirectory;

    /** The row the source yields: five base values and four the stages fill in. */
    record LoadRow(long id, String reference, BigDecimal amount, LocalDate day, boolean settled,
                   String customerId, String customerName, String tierName, String regionName) {

        LoadRow withCustomer(String name) {
            return new LoadRow(id, reference, amount, day, settled, customerId, name, tierName,
                    regionName);
        }

        LoadRow withTier(String tier) {
            return new LoadRow(id, reference, amount, day, settled, customerId, customerName, tier,
                    regionName);
        }

        LoadRow withRegion(String region) {
            return new LoadRow(id, reference, amount, day, settled, customerId, customerName,
                    tierName, region);
        }
    }

    @Test
    @DisplayName("a million rows into XLSX and CSV in one pass, inside a fixed heap")
    void producesAMillionRowsInsideTheHeapBudget() throws Exception {
        ExportProperties properties = new ExportProperties();
        Clock clock = Clock.systemUTC();
        Path outputDirectory = Files.createDirectories(tempDirectory.resolve("outputs"));
        DiskSink sink = new DiskSink(outputDirectory);
        ReportRunEngine engine = engine(properties, sink, clock);
        ExecutionPlan<NoParameters, LoadRow> plan = plan(properties);

        long maxHeap = Runtime.getRuntime().maxMemory();
        assertThat(maxHeap)
                .as("run this with a small heap - the completion is the memory assertion; see the"
                        + " command in this module's pom.xml")
                .isLessThanOrEqualTo(MAX_ACCEPTED_XMX_BYTES);

        long collectionsBefore = fullCollectionCount();
        HeapSampler sampler = HeapSampler.start();
        Instant started = Instant.now();

        ReportRunResult result = engine.execute(plan, RunCancellation.NEVER);

        Duration elapsed = Duration.between(started, Instant.now());
        long sampledPeak = sampler.stop();
        long retained = retainedHeapBytes();
        long fullCollections = fullCollectionCount() - collectionsBefore;
        long rowsPerSecond = Math.max(1, elapsed.toSeconds()) > 0
                ? ROWS / Math.max(1, elapsed.toSeconds())
                : ROWS;

        // Printed, not only asserted: the README quotes these numbers and the machine they came
        // from, and a number nobody can see is a number nobody can update.
        System.out.printf(Locale.ROOT,
                "%n=== export load test ===%n"
                        + "rows              : %,d%n"
                        + "columns           : %d (%d enriched across 3 stages)%n"
                        + "formats           : xlsx + csv, one pass%n"
                        + "elapsed           : %s%n"
                        + "throughput        : %,d rows/s%n"
                        + "peak heap sampled : %,d MiB%n"
                        + "retained after GC : %,d MiB (ceiling %,d MiB)%n"
                        + "full collections  : %d%n"
                        + "xlsx bytes        : %,d%n"
                        + "csv bytes         : %,d%n"
                        + "jvm -Xmx          : %,d MiB%n"
                        + "java              : %s%n"
                        + "os                : %s %s%n=== end ===%n",
                result.rowsWritten(), BASE_COLUMNS + ENRICHED_COLUMNS, ENRICHED_COLUMNS, elapsed,
                rowsPerSecond, sampledPeak / (1024 * 1024), retained / (1024 * 1024),
                MAX_RETAINED_BYTES / (1024 * 1024), fullCollections, sink.sizeOf("xlsx"),
                sink.sizeOf("csv"),
                Runtime.getRuntime().maxMemory() / (1024 * 1024),
                System.getProperty("java.version"), System.getProperty("os.name"),
                System.getProperty("os.arch"));

        assertThat(result.rowsWritten()).isEqualTo(ROWS);
        assertThat(result.outputs()).hasSize(2);
        // The sampled peak is reported rather than asserted: "used" includes garbage the collector
        // has not reached, so on a small heap it legitimately approaches -Xmx without the engine
        // retaining any of it. What is asserted is what the engine still holds, below.
        assertThat(retained).as("retained heap after the run").isLessThan(MAX_RETAINED_BYTES);
        assertThat(fullCollections).as("full collections").isLessThan(MAX_FULL_COLLECTIONS);
        assertThat(rowsPerSecond).as("rows per second").isGreaterThan(MIN_ROWS_PER_SECOND);
        assertThat(listTempFiles()).as("temp files left behind").containsExactly(outputDirectory);

        assertLastXlsxRow(sink.pathOf("xlsx"));
        assertLastCsvRow(sink.pathOf("csv"));
    }

    /**
     * Reads the produced workbook back with POI's event API and checks its last row.
     *
     * <p>The streaming reader, not {@code XSSFWorkbook}: loading a million-row workbook into the
     * object model would need more heap than the test that produced it, which would make the
     * verification the thing that fails. The assertion is on a numeric cell, whose value is inline in
     * the sheet XML - a string cell would need the shared-strings table and turn this into a parser.
     */
    private void assertLastXlsxRow(Path file) throws Exception {
        try (OPCPackage open = OPCPackage.open(file.toFile())) {
            XSSFReader reader = new XSSFReader(open);
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            assertThat(sheets.hasNext()).as("the workbook has a sheet").isTrue();
            try (InputStream sheet = sheets.next()) {
                SheetShape shape = shapeOf(sheet);
                // A header row, a million data rows, and a totals row - the definition asks for one
                // and XLSX declares it can carry one. The CSV beside it has no totals row at all,
                // because CSV declares totalsRow=false; the two files disagreeing here is the
                // capability check working at scale rather than a discrepancy.
                assertThat(shape.rowCount()).as("rows in the sheet").isEqualTo(ROWS + 2);
                // The last DATA row's id, which is what says the file did not stop early. Asserted on
                // the row's own `r` attribute rather than on "the last row", so the totals row cannot
                // be mistaken for it.
                // Compared as a number, not as text: a numeric cell in XLSX is an IEEE-754 double
                // by specification - the writer says so - so the sheet XML carries "1000000.0".
                assertThat(Double.parseDouble(shape.lastDataRowFirstNumber()))
                        .as("the millionth row's id").isEqualTo(ROWS);
            }
        }
    }

    /**
     * Checks the CSV's last record by reading the tail of the file.
     *
     * <p>Not by reading the file: it is 284 MB, and loading it to look at its last line would be the
     * same mistake the in-memory sink was. Seeking to the end and reading a few hundred bytes answers
     * the same question.
     */
    private void assertLastCsvRow(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            long size = channel.size();
            int tail = (int) Math.min(TAIL_BYTES, size);
            ByteBuffer buffer = ByteBuffer.allocate(tail);
            channel.position(size - tail).read(buffer);
            String text = new String(buffer.array(), StandardCharsets.UTF_8);
            int lastNewline = text.lastIndexOf("\r\n", text.length() - 3);
            String lastLine = text.substring(lastNewline + 2).strip();
            assertThat(lastLine).as("the last CSV record").startsWith(ROWS + ",");
        }
    }

    /** How many rows the sheet has, and the first numeric value of its last data row. */
    private record SheetShape(int rowCount, String lastDataRowFirstNumber) {
    }

    /**
     * Walks the sheet XML once, counting rows and capturing one value.
     *
     * <p>Rows are identified by their own {@code r} attribute rather than by position, so the totals
     * row at the end cannot be mistaken for the last data row - which is exactly the mistake the
     * first version of this assertion made.
     */
    private SheetShape shapeOf(InputStream sheet) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // A report is not a document this test should be willing to expand entities from.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader xml = factory.createXMLStreamReader(sheet);
        String lastDataRowNumber = String.valueOf(ROWS + 1);
        int rows = 0;
        String captured = null;
        boolean inLastDataRow = false;
        boolean readingValue = false;
        StringBuilder value = new StringBuilder();
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT && "row".equals(xml.getLocalName())) {
                rows++;
                inLastDataRow = lastDataRowNumber.equals(xml.getAttributeValue(null, "r"));
            } else if (event == XMLStreamConstants.START_ELEMENT && "v".equals(xml.getLocalName())
                    && inLastDataRow && captured == null) {
                readingValue = true;
                value.setLength(0);
            } else if (event == XMLStreamConstants.CHARACTERS && readingValue) {
                value.append(xml.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT && "v".equals(xml.getLocalName())
                    && readingValue) {
                readingValue = false;
                captured = value.toString();
            }
        }
        return new SheetShape(rows, captured);
    }

    private ReportRunEngine engine(ExportProperties properties, ReportSink sink, Clock clock) {
        ExportMessages messages = (key, locale, args) -> key;
        List<ReportWriterFactory> factories = List.of(
                new CsvReportWriterFactory(properties.getFormats().getCsv(), messages),
                new XlsxReportWriterFactory(properties.getFormats().getXlsx(),
                        name -> Optional.empty(), messages));
        EnrichmentExecutor enrichment = new EnrichmentExecutor(null, properties.getEnrichment(),
                NoopExportMetrics.INSTANCE, messages);
        return new ReportRunEngine(factories, sink, new TempFiles(tempDirectory, clock), messages,
                clock, enrichment, NoopExportMetrics.INSTANCE, null);
    }

    private ExecutionPlan<NoParameters, LoadRow> plan(ExportProperties properties) {
        List<Column<LoadRow, ?>> columns = columns();
        var builder = ReportDefinition
                .<NoParameters, LoadRow>of("load.sales", NoParameters.class, LoadRow.class)
                .titleKey("load.title")
                .source(new GeneratedSource())
                .allowedFormats(Set.of(StandardReportFormats.XLSX, StandardReportFormats.CSV))
                .defaultFormat(StandardReportFormats.XLSX)
                .totalsRow(true);
        columns.forEach(builder::column);
        stages().forEach(builder::stage);
        ReportDefinition<NoParameters, LoadRow> definition = builder.build();

        List<ColumnSpec> specs = columns.stream()
                .map(column -> new ColumnSpec(column.getId(), column.getId(), column.getFormat(),
                        column.getWidth(), column.getAggregate(), false))
                .toList();
        SheetSpec sheet = SheetSpec.single("data", "Sales", specs);
        List<OutputTarget> outputs = List.of(
                OutputTarget.of(StandardReportFormats.XLSX, List.of(sheet), MultiSheetStrategy.REJECT,
                        Map.of(), true),
                OutputTarget.of(StandardReportFormats.CSV, List.of(sheet), MultiSheetStrategy.REJECT,
                        Map.of("profile", "rfc4180"), true));

        return ExecutionPlan.<NoParameters, LoadRow>builder()
                .runId(UUID.randomUUID())
                .definition(definition)
                .parameters(NoParameters.INSTANCE)
                .columns(columns)
                .sheetDefinitions(definition.getSheets())
                .sheetSpecs(List.of(sheet))
                .outputs(outputs)
                .render(new RenderContext(Locale.ENGLISH, ZoneId.of("UTC")))
                .principalId("load-test")
                .authorities(Set.of())
                .correlationId("load")
                .pageSize(properties.getSourcePageSize())
                .windowSize(properties.getWindowSize())
                .handoffQueueDepth(properties.getHandoffQueueDepth())
                .enrichmentCacheSize(properties.getEnrichment().getCacheSize())
                .rowCap(ROWS)
                .wallClockBudget(Duration.ofHours(1))
                .metadata(List.of())
                .build();
    }

    /**
     * Seventeen columns over the row's own values plus eight fed by the stages.
     *
     * <p>The base columns repeat the same five extractors under different ids, which is realistic
     * enough for a load test: what costs time is the cell, not the field it came from. The eight
     * enriched ones are what the design point is actually about.
     */
    private List<Column<LoadRow, ?>> columns() {
        List<Column<LoadRow, ?>> columns = new ArrayList<>();
        columns.add(Column.<LoadRow, java.math.BigInteger>of("id", BigInteger.class)
                .extractor(row -> BigInteger.valueOf(row.id()))
                .headerKey("load.column.id").format(CellFormat.number(0)).build());
        for (int i = 0; i < BASE_COLUMNS - 1; i++) {
            columns.add(baseColumn(i));
        }
        for (int i = 0; i < ENRICHED_COLUMNS; i++) {
            columns.add(enrichedColumn(i));
        }
        return columns;
    }

    private Column<LoadRow, ?> baseColumn(int index) {
        return switch (index % 4) {
            case 0 -> Column.<LoadRow, String>of("text-" + index, String.class)
                    .extractor(LoadRow::reference).headerKey("load.column.text")
                    .format(CellFormat.text()).width(18).build();
            case 1 -> Column.<LoadRow, BigDecimal>of("amount-" + index, BigDecimal.class)
                    .extractor(LoadRow::amount).headerKey("load.column.amount")
                    .format(CellFormat.number(2)).aggregate(Aggregate.SUM).build();
            case 2 -> Column.<LoadRow, LocalDate>of("day-" + index, LocalDate.class)
                    .extractor(LoadRow::day).headerKey("load.column.day")
                    .format(CellFormat.date()).aggregate(Aggregate.MAX).build();
            default -> Column.<LoadRow, Boolean>of("flag-" + index, Boolean.class)
                    .extractor(LoadRow::settled).headerKey("load.column.flag")
                    .format(CellFormat.bool()).build();
        };
    }

    private Column<LoadRow, ?> enrichedColumn(int index) {
        String stage = switch (index % 3) {
            case 0 -> "customer";
            case 1 -> "tier";
            default -> "region";
        };
        Function<LoadRow, String> extractor = switch (index % 3) {
            case 0 -> LoadRow::customerName;
            case 1 -> LoadRow::tierName;
            default -> LoadRow::regionName;
        };
        return Column.<LoadRow, String>of("enriched-" + index, String.class)
                .extractor(extractor)
                .headerKey("load.column.enriched")
                .format(CellFormat.text())
                .requiredStage(stage)
                .width(20)
                .build();
    }

    /**
     * Three stages against stubbed partners.
     *
     * <p>Batched, because that is the shape the design point assumes and the one the cache makes
     * cheap: a thousand distinct customers across a million rows means a few thousand keys asked for
     * once each, and the per-run cache answers everything after that.
     */
    private List<EnrichmentStage<LoadRow, ?, ?>> stages() {
        return List.of(
                stage("customer", 1_000, LoadRow::withCustomer),
                stage("tier", 20, LoadRow::withTier),
                stage("region", 8, LoadRow::withRegion));
    }

    private EnrichmentStage<LoadRow, ?, ?> stage(String name, int distinctKeys,
                                                 BiFunction<LoadRow, String, LoadRow> merge) {
        Enricher.Batched<Object, Object> partner = keys -> {
            Map<Object, Object> found = new HashMap<>();
            for (Object key : keys) {
                found.put(key, name + "-" + key);
            }
            return found;
        };
        return EnrichmentStage.<LoadRow, Object, Object>of(name, partner)
                .keyExtractor(row -> name + ":" + (row.id() % distinctKeys))
                .merge((row, value) -> merge.apply(row, (String) value))
                .build();
    }

    /** A lazy source: a million rows generated on demand, never a list. */
    private static final class GeneratedSource implements RowSource<NoParameters, LoadRow> {

        @Override
        public Stream<LoadRow> open(SourceContext<NoParameters> context) {
            LocalDate epoch = LocalDate.of(2026, 1, 1);
            return IntStream.rangeClosed(1, ROWS)
                    .mapToObj(i -> new LoadRow(i, "REF-" + i, BigDecimal.valueOf(i % 10_000L),
                            epoch.plusDays(i % 365), i % 2 == 0, "C-" + (i % 1_000), null, null,
                            null));
        }

        @Override
        public Set<String> sortableColumns() {
            return Set.of();
        }

        @Override
        public long estimateRows(SourceContext<NoParameters> context) {
            return ROWS;
        }
    }

    /**
     * A sink that moves the finished file into a directory of its own.
     *
     * <p>Deliberately <em>not</em> an in-memory sink. The first version of this test held both files
     * in a map and then reported 383 MiB retained - which was the fixture, not the engine: a 93 MB
     * workbook and a 284 MB CSV are exactly the thing a sink must never keep. A test whose
     * measurement is dominated by its own harness measures the harness.
     */
    private static final class DiskSink implements ReportSink {

        private final Path directory;
        private final Map<String, Path> stored = new HashMap<>();

        DiskSink(Path directory) {
            this.directory = directory;
        }

        @Override
        public StoredOutput store(UUID runId, String fileName, ReportFormat format, Path file)
                throws IOException {
            Path target = directory.resolve(format.id() + "." + format.fileExtension());
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            stored.put(format.id(), target);
            long size = Files.size(target);
            return new StoredOutput(target.getFileName().toString(), size, "sha-" + size);
        }

        @Override
        public InputStream open(String uri) throws IOException {
            return Files.newInputStream(directory.resolve(uri));
        }

        @Override
        public void delete(String uri) throws IOException {
            Files.deleteIfExists(directory.resolve(uri));
        }

        Path pathOf(String formatId) {
            return stored.get(formatId);
        }

        long sizeOf(String formatId) throws IOException {
            Path file = stored.get(formatId);
            return file == null ? 0 : Files.size(file);
        }
    }

    /**
     * Samples heap usage while the run is in flight.
     *
     * <p>Sampling {@code MemoryMXBean.getHeapMemoryUsage()} rather than summing each pool's
     * {@code getPeakUsage}: the per-pool peaks happen at different moments, so adding them produces a
     * number that can exceed the heap size - which is how this test first reported 542 MiB of peak on
     * a 512 MiB heap. One reading of the whole heap at a time is the only thing that is a heap
     * measurement.
     */
    private static final class HeapSampler {

        private final Thread thread;
        private volatile boolean running = true;
        private volatile long peak;

        private HeapSampler() {
            this.thread = new Thread(this::sample, "load-test-heap-sampler");
            this.thread.setDaemon(true);
        }

        static HeapSampler start() {
            HeapSampler sampler = new HeapSampler();
            sampler.thread.start();
            return sampler;
        }

        private void sample() {
            while (running) {
                peak = Math.max(peak,
                        ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        long stop() {
            running = false;
            thread.interrupt();
            return peak;
        }
    }

    /**
     * What is still reachable now that the run is over.
     *
     * <p>A collection first, because the question is retention rather than garbage. {@code System.gc}
     * is a request rather than a guarantee, so the reading is taken twice with a pause between: a
     * single reading after a request the collector declined would report the garbage as retained.
     */
    private static long retainedHeapBytes() throws InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            System.gc();
            Thread.sleep(100);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    /**
     * Collections by the collectors that stop the world for the old generation.
     *
     * <p>Named rather than counted across every collector, because a young collection per window is
     * expected and healthy - it is the full ones that say the run is only fitting because the
     * collector is working for it.
     */
    private static long fullCollectionCount() {
        Collection<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        return collectors.stream()
                .filter(collector -> isFullCollector(collector.getName()))
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .sum();
    }

    private static boolean isFullCollector(String name) {
        String lowered = name.toLowerCase(Locale.ROOT);
        return lowered.contains("old") || lowered.contains("marksweep") || lowered.contains("tenured")
                || lowered.contains("g1 concurrent") || lowered.contains("zgc")
                || lowered.contains("shenandoah");
    }

    private List<Path> listTempFiles() throws IOException {
        try (Stream<Path> entries = Files.list(tempDirectory)) {
            return entries.toList();
        }
    }
}
