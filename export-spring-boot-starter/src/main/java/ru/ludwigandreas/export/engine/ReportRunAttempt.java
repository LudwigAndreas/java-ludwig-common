package ru.ludwigandreas.export.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.export.api.CellValue;
import ru.ludwigandreas.export.api.MetadataEntry;
import ru.ludwigandreas.export.enrich.EnrichedRow;
import ru.ludwigandreas.export.enrich.EnrichmentRun;
import ru.ludwigandreas.export.enrich.WindowEnrichment;
import ru.ludwigandreas.export.api.RenderedRow;
import ru.ludwigandreas.export.api.ReportParameters;
import ru.ludwigandreas.export.api.ReportWriter;
import ru.ludwigandreas.export.api.ReportWriterFactory;
import ru.ludwigandreas.export.api.SheetDefinition;
import ru.ludwigandreas.export.api.SheetSpec;
import ru.ludwigandreas.export.api.StoredOutput;
import ru.ludwigandreas.export.api.WriterContext;
import ru.ludwigandreas.export.exception.ExportConfigurationException;
import ru.ludwigandreas.export.exception.ExportException;
import ru.ludwigandreas.export.exception.ExportProblemCodes;
import ru.ludwigandreas.export.exception.ExportWriteException;
import ru.ludwigandreas.export.exception.ReportCancelledException;
import ru.ludwigandreas.export.exception.ReportLimitExceededException;
import ru.ludwigandreas.export.exception.SinkUnavailableException;
import ru.ludwigandreas.export.render.RowRenderer;

/**
 * One execution of one plan. Created per attempt, used once, thrown away.
 *
 * <p>A separate object from {@link ReportRunEngine} because a run has a great deal of state - how
 * many rows so far, which sheet is open, which sheets are already closed, the running totals - and
 * the alternative is either a long parameter list threaded through every method or mutable state on
 * a shared bean. The second would make two concurrent runs corrupt each other's totals, which is a
 * defect that appears only under load and presents as a wrong number in a finished file.
 *
 * <h2>Sheet routing and the contiguity rule</h2>
 *
 * <p>Rows are routed to sheets by the definition's discriminators, and a sheet is opened when its
 * first row arrives and closed when a row for another sheet does. That means the source's order has
 * to group a sheet's rows together - see {@code SheetDefinition} for why buffering or holding every
 * sheet open are both unacceptable at the design point.
 *
 * <p>The rule is checked rather than assumed. A row arriving for a sheet that has already been
 * closed fails the run naming the sheet, because the alternative is a workbook with the same sheet
 * twice, or - worse - rows silently written into whichever sheet happened to be open.
 *
 * @param <P> the definition's parameter type
 * @param <R> the row type
 */
@Slf4j
final class ReportRunAttempt<P extends ReportParameters, R> {

    private final ExecutionPlan<P, R> plan;
    private final RunCancellation cancellation;
    private final EngineServices services;

    private final Map<String, SheetSpec> specsById;
    private final RowRenderer<R> renderer;
    private final EnrichmentRun<R> enrichment;
    private final List<OpenOutput> open = new ArrayList<>();

    private Instant startedAt;
    private long rowsWritten;
    private long rowsDropped;
    private String currentSheetId;
    private TotalsAccumulator currentTotals;
    private final Set<String> closedSheets = new LinkedHashSet<>();

    ReportRunAttempt(ExecutionPlan<P, R> plan, RunCancellation cancellation, EngineServices services) {
        this.plan = plan;
        this.cancellation = cancellation;
        this.services = services;
        this.specsById = index(plan.getSheetSpecs());
        this.renderer = new RowRenderer<>(plan.getColumns(), plan.getRender(), services.messages());
        this.enrichment = services.enrichment().startRun(plan.getDefinition().getKey(),
                plan.getDefinition().getStages(), plan.getRender().locale(),
                plan.getEnrichmentCacheSize());
    }

    /** Executes the plan, cleaning up on every exit path. */
    ReportRunResult run() {
        startedAt = services.clock().instant();
        try {
            openOutputs();
            walk();
            List<ReportOutput> stored = finishAndStore();
            Duration elapsed = Duration.between(startedAt, services.clock().instant());
            log.info("Report run {} for {} wrote {} rows ({} dropped) into {} output(s) in {};"
                            + " cache {} hits / {} misses, degraded stages: {}",
                    plan.getRunId(), plan.getDefinition().getKey(), rowsWritten, rowsDropped,
                    stored.size(), elapsed, enrichment.cache().hits(), enrichment.cache().misses(),
                    enrichment.degradedStages());
            return new ReportRunResult(plan.getRunId(), rowsWritten, elapsed, stored,
                    omittedSheets(), enrichment.degradedStages());
        } finally {
            // Unconditional, and in this order: a writer may still hold the file handle the delete
            // needs on some filesystems, and a failed close must not stop the delete.
            closeWriters();
            deleteTempFiles();
        }
    }

    private void openOutputs() {
        for (OutputTarget target : plan.getOutputs()) {
            ReportWriterFactory factory = services.factories().get(target.format().id());
            if (factory == null) {
                throw new ExportConfigurationException(
                        "Report run " + plan.getRunId() + " asks for format '" + target.format().id()
                                + "', for which no ReportWriterFactory is registered");
            }
            Path file;
            try {
                file = services.tempFiles().create(plan.getRunId(), target.fileExtension());
            } catch (IOException e) {
                throw new ExportWriteException(target.format().id(), e);
            }
            OpenOutput output = new OpenOutput(target, file);
            // Registered before the writer is created, so that a factory which fails after opening
            // the file still leaves the path where the finally block can find it.
            open.add(output);
            try {
                output.writer = factory.create(writerContext(target, file));
            } catch (IOException e) {
                throw new ExportWriteException(target.format().id(), e);
            }
        }
    }

    private WriterContext writerContext(OutputTarget target, Path file) {
        return new WriterContext(plan.getRunId(), target.format(), file, target.sheets(),
                target.strategy(), plan.getRender(), plan.getMetadata(), target.options(),
                target.totalsRow(), plan.getDefinition().getTemplate());
    }

    private void walk() {
        try (WindowPump<WindowEnrichment<R>> pump = WindowPump.start(services.prefetchExecutor(),
                plan.getHandoffQueueDepth(),
                () -> plan.getDefinition().getSource().open(plan.sourceContext()),
                this::enrichedWindows)) {
            for (Optional<WindowEnrichment<R>> next = pump.next(); next.isPresent();
                    next = pump.next()) {
                checkCancelled();
                checkWallClock();
                writeWindow(next.get());
            }
        }
        closeCurrentSheet();
        writeUnvisitedSheets();
    }

    /**
     * The producer side: raw windows in, enriched windows out, one at a time.
     *
     * <p>An iterator rather than a stream of collected windows, because the whole contract of the
     * pump is that exactly one window exists between the source and the queue. It checks
     * cancellation itself as well as the consumer doing so, which is what stops a cancelled run from
     * continuing to call partners for as long as the queue takes to drain.
     */
    private Iterator<WindowEnrichment<R>> enrichedWindows(Stream<R> rows) {
        Iterator<R> source = rows.iterator();
        return new Iterator<>() {

            @Override
            public boolean hasNext() {
                return source.hasNext();
            }

            @Override
            public WindowEnrichment<R> next() {
                checkCancelled();
                return enrichment.enrich(WindowPump.fill(source, plan.getWindowSize()));
            }
        };
    }

    private void writeWindow(WindowEnrichment<R> window) {
        rowsDropped += window.dropped();
        for (EnrichedRow<R> enriched : window.rows()) {
            R row = enriched.row();
            String sheetId = routeSheet(row);
            if (!sheetId.equals(currentSheetId)) {
                switchSheet(sheetId);
            }
            rowsWritten++;
            checkRowCap();
            List<CellValue> cells = renderer.render(row, rowsWritten, enriched.markers());
            currentTotals.accept(cells);
            RenderedRow rendered = new RenderedRow(cells, sheetId);
            for (OpenOutput output : open) {
                if (output.target.accepts(sheetId)) {
                    write(output, () -> output.writer.writeRow(rendered));
                }
            }
        }
    }

    private String routeSheet(R row) {
        for (SheetDefinition<R> sheet : plan.getSheetDefinitions()) {
            if (sheet.accepts(row)) {
                return sheet.id();
            }
        }
        // A discriminator returned something no declared sheet answers to, which means the
        // definition and the data disagree. Writing the row into whichever sheet is open would put
        // it under a heading that is wrong for it.
        throw new ExportException("Report run " + plan.getRunId() + " produced a row that belongs to"
                + " no declared sheet of " + plan.getDefinition().getKey());
    }

    private void switchSheet(String sheetId) {
        if (closedSheets.contains(sheetId)) {
            throw new ExportException("Report run " + plan.getRunId() + " returned to sheet '" + sheetId
                    + "' after it was closed: the row source's order must group a sheet's rows"
                    + " together, leading with the same expression the sheet discriminator reads");
        }
        closeCurrentSheet();
        currentSheetId = sheetId;
        SheetSpec spec = requireSpec(sheetId);
        currentTotals = new TotalsAccumulator(spec.columns(), plan.getRender().zone());
        for (OpenOutput output : open) {
            if (output.target.accepts(sheetId)) {
                write(output, () -> output.writer.beginSheet(spec));
            }
        }
    }

    private void closeCurrentSheet() {
        if (currentSheetId == null) {
            return;
        }
        List<CellValue> totals = currentTotals.totals();
        for (OpenOutput output : open) {
            if (output.target.accepts(currentSheetId)) {
                write(output, () -> output.writer.endSheet(totals));
            }
        }
        closedSheets.add(currentSheetId);
        currentSheetId = null;
        currentTotals = null;
    }

    /**
     * Writes the header of every sheet no row was routed to.
     *
     * <p>A declared sheet that produced nothing is still part of the document. Leaving it out would
     * make "this sheet is missing" and "this sheet is empty" the same file, and the two mean
     * opposite things to somebody checking whether a category had any activity this month.
     */
    private void writeUnvisitedSheets() {
        for (SheetSpec spec : plan.getSheetSpecs()) {
            if (closedSheets.contains(spec.id())) {
                continue;
            }
            List<CellValue> empty = new TotalsAccumulator(spec.columns(), plan.getRender().zone()).totals();
            for (OpenOutput output : open) {
                if (output.target.accepts(spec.id())) {
                    write(output, () -> output.writer.beginSheet(spec));
                    write(output, () -> output.writer.endSheet(empty));
                }
            }
            closedSheets.add(spec.id());
        }
    }

    private List<ReportOutput> finishAndStore() {
        List<ReportOutput> stored = new ArrayList<>(open.size());
        String title = services.messages().resolve(plan.getDefinition().getTitleKey(), plan.getRender().locale());
        List<MetadataEntry> late = runOutcomeMetadata();
        for (OpenOutput output : open) {
            Path finished;
            try {
                // Before finish, because a writer that materialises its metadata sheet does so there
                // and would otherwise write one that answers only the questions that were easy.
                output.writer.addMetadata(late);
                finished = output.writer.finish();
            } catch (IOException e) {
                throw new ExportWriteException(output.target.format().id(), e);
            }
            // Closed before the sink reads it: a writer may still be holding buffered bytes, and a
            // sink that hashed the file before the last flush would record a checksum for a file
            // that does not exist by the time anyone downloads it.
            output.closeWriter();
            String fileName = ReportFileNames.of(title, plan.getRunId(), output.target.fileExtension());
            stored.add(store(output, finished, fileName));
        }
        return stored;
    }

    /**
     * What the run turned out to be, for the metadata sheet.
     *
     * <p>Built once and given to every writer, so two formats of the same run cannot disagree about
     * how many rows it produced.
     *
     * <p>Degraded stages and omitted sheets are written <em>even when there are none</em>, as an
     * explicit "none". An absent row is indistinguishable from a writer that forgot, and the whole
     * point of this sheet is that somebody holding the file months later can tell the difference
     * between a complete report and one nobody told them about.
     */
    private List<MetadataEntry> runOutcomeMetadata() {
        Locale locale = plan.getRender().locale();
        Instant completedAt = services.clock().instant();
        List<String> degraded = enrichment.degradedStages();
        List<String> omitted = omittedSheets();
        String none = services.messages().resolve("ludwig.export.metadata.none", locale);
        List<MetadataEntry> entries = new ArrayList<>();
        entries.add(outcome("ludwig.export.metadata.run-id", locale, plan.getRunId().toString()));
        entries.add(outcome("ludwig.export.metadata.row-count", locale,
                NumberFormat.getIntegerInstance(locale).format(rowsWritten)));
        entries.add(outcome("ludwig.export.metadata.requested-at", locale, format(startedAt, locale)));
        entries.add(outcome("ludwig.export.metadata.completed-at", locale, format(completedAt, locale)));
        entries.add(outcome("ludwig.export.metadata.degraded-stages", locale,
                degraded.isEmpty() ? none : String.join(", ", degraded)));
        entries.add(outcome("ludwig.export.metadata.sheets-omitted", locale,
                omitted.isEmpty() ? none : String.join(", ", omitted)));
        if (plan.getCorrelationId() != null && !plan.getCorrelationId().isBlank()) {
            entries.add(outcome("ludwig.export.metadata.correlation-id", locale, plan.getCorrelationId()));
        }
        return List.copyOf(entries);
    }

    private MetadataEntry outcome(String labelKey, Locale locale, String value) {
        return new MetadataEntry(services.messages().resolve(labelKey, locale), value);
    }

    /**
     * An instant as the run's own timezone renders it, not as the machine's.
     *
     * <p>The same zone the date cells use, because a metadata sheet that timestamped the run in UTC
     * beside data timestamped in the requester's zone invites exactly the wrong conclusion.
     */
    private String format(Instant instant, Locale locale) {
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(locale)
                .format(instant.atZone(plan.getRender().zone()));
    }

    private ReportOutput store(OpenOutput output, Path finished, String fileName) {
        try {
            StoredOutput result = services.sink().store(plan.getRunId(), fileName, output.target.format(), finished);
            return new ReportOutput(output.target.format().id(), output.target.mediaType(), fileName,
                    result);
        } catch (IOException e) {
            throw new SinkUnavailableException(output.target.format().id(), e);
        }
    }

    private void checkCancelled() {
        if (cancellation.isCancelled()) {
            throw new ReportCancelledException(plan.getRunId(), rowsWritten);
        }
    }

    private void checkWallClock() {
        Duration elapsed = Duration.between(startedAt, services.clock().instant());
        if (elapsed.compareTo(plan.getWallClockBudget()) > 0) {
            throw new ReportLimitExceededException(ExportProblemCodes.TIME_BUDGET_EXCEEDED,
                    "ludwig.export.wall-clock-budget", plan.getWallClockBudget().toSeconds(),
                    elapsed.toSeconds());
        }
    }

    private void checkRowCap() {
        if (rowsWritten > plan.getRowCap()) {
            throw new ReportLimitExceededException(ExportProblemCodes.ROW_LIMIT_EXCEEDED,
                    "ludwig.export.max-rows-per-run", plan.getRowCap(), rowsWritten);
        }
    }

    private void write(OpenOutput output, WriteStep step) {
        try {
            step.run();
        } catch (IOException e) {
            throw new ExportWriteException(output.target.format().id(), e);
        }
    }

    private SheetSpec requireSpec(String sheetId) {
        SheetSpec spec = specsById.get(sheetId);
        if (spec == null) {
            throw new ExportConfigurationException("Report run " + plan.getRunId() + " routed a row to"
                    + " sheet '" + sheetId + "', for which the plan carries no resolved spec");
        }
        return spec;
    }

    private List<String> omittedSheets() {
        Set<String> omitted = new LinkedHashSet<>();
        for (OutputTarget target : plan.getOutputs()) {
            omitted.addAll(target.omittedSheets());
        }
        return List.copyOf(omitted);
    }

    private void closeWriters() {
        for (OpenOutput output : open) {
            output.closeWriter();
        }
    }

    private void deleteTempFiles() {
        for (OpenOutput output : open) {
            services.tempFiles().deleteQuietly(output.file);
        }
    }

    private static Map<String, SheetSpec> index(List<SheetSpec> specs) {
        Map<String, SheetSpec> byId = new LinkedHashMap<>();
        specs.forEach(spec -> byId.put(spec.id(), spec));
        return Map.copyOf(byId);
    }

    /** One writer's worth of per-run state: what it is writing, where, and whether it is still open. */
    private static final class OpenOutput {

        private final OutputTarget target;
        private final Path file;
        private ReportWriter writer;
        private boolean closed;

        OpenOutput(OutputTarget target, Path file) {
            this.target = target;
            this.file = file;
        }

        void closeWriter() {
            if (writer == null || closed) {
                return;
            }
            closed = true;
            writer.close();
        }
    }

    /** One call into a writer, so that the IOException translation is written once. */
    @FunctionalInterface
    private interface WriteStep {

        void run() throws IOException;
    }
}
