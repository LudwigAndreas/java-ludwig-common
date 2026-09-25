package ru.ludwigandreas.export.engine;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import ru.ludwigandreas.export.api.ReportParameters;
import ru.ludwigandreas.export.api.ReportSink;
import ru.ludwigandreas.export.api.ReportWriterFactory;
import ru.ludwigandreas.export.exception.ExportConfigurationException;
import ru.ludwigandreas.export.enrich.EnrichmentExecutor;
import ru.ludwigandreas.export.i18n.ExportMessages;
import ru.ludwigandreas.export.metrics.ExportMetrics;
import ru.ludwigandreas.export.metrics.NoopExportMetrics;

/**
 * Runs a report: one pass over the source, into every requested format at once.
 *
 * <h2>Where the million-row requirement is won</h2>
 *
 * <ol>
 *   <li><b>Nothing is ever fully materialised.</b> The source is a lazy, keyset-paginated stream;
 *       the engine reads it a window at a time and holds exactly one window. There is no
 *       {@code List<R> all} in this class, and adding one would not be a performance regression but
 *       a functional one - the report would stop working at the size it exists for.</li>
 *   <li><b>One pass feeds every format.</b> A row is rendered into typed cells once and handed to
 *       each writer. The alternative - a pass per format - would pay the enrichment calls twice,
 *       and at the design point those dominate everything else by an order of magnitude.</li>
 *   <li><b>Totals are folded as the rows go past.</b> No second pass over the output, ever.</li>
 *   <li><b>The limits are enforced against real counts</b>, not against the estimate that decided
 *       how to schedule the run.</li>
 * </ol>
 *
 * <h2>Why there is no writer thread</h2>
 *
 * <p>The writer runs on the thread that walks the source, and the bounded hand-off that
 * {@code ludwig.export.handoff-queue-depth} configures does not exist yet. That is deliberate rather
 * than pending: a queue decouples a producer from a consumer, and until the enrichment executor
 * lands there is no concurrent producer to decouple from - a dedicated writer thread today would
 * cost a thread per run and buy nothing but a hand-off. When enrichment arrives, the prefetch
 * happens on <em>its</em> executor, which is where the waiting actually is, and the writer stays on
 * this thread. The property is validated at startup and documented as taking effect then.
 *
 * <h2>Cleanup is unconditional</h2>
 *
 * <p>Every exit path - success, failure, cancellation, a sink that refused the file - closes the
 * stream, closes every writer and deletes every temp file. At the design point a leaked temp file
 * is four hundred megabytes, so this is not tidiness: a reporting instance that leaks one per failed
 * run fills its disk within a day.
 */
public class ReportRunEngine {

    private final Map<String, ReportWriterFactory> factories;
    private final ReportSink sink;
    private final TempFiles tempFiles;
    private final ExportMessages messages;
    private final Clock clock;
    private final EnrichmentExecutor enrichment;
    private final ExportMetrics metrics;

    /**
     * Where a run's producer thread comes from.
     *
     * <p>Deliberately separate from the pool a stage's calls fan out on. A producer blocks waiting
     * for the enrichment futures it submitted, so sharing one pool between the two would let
     * producers occupy every thread and then wait for work that can never be scheduled - a deadlock
     * that appears only when enough runs are in flight at once, which is to say in production.
     */
    private final ExecutorService prefetchExecutor;

    /**
     * Creates the engine.
     *
     * @param factories the registered writer factories; a plan naming a format absent here is a
     *                  configuration failure rather than a request failure, because the registry
     *                  already refused any definition that allowed one
     * @param sink      where finished files go
     * @param tempFiles where partial files live, and what removes them
     * @param messages  resolves headers, titles and markers into the run's locale
     * @param clock     injected so the wall-clock budget is deterministic in tests
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a Spring bean assembled in one @Bean method, where every
    // argument is named by its own bean; there is no positional call site for the rule to protect.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReportRunEngine(List<ReportWriterFactory> factories, ReportSink sink, TempFiles tempFiles,
                           ExportMessages messages, Clock clock, EnrichmentExecutor enrichment,
                           ExportMetrics metrics, ExecutorService prefetchExecutor) {
        this.factories = index(factories);
        this.sink = sink;
        this.tempFiles = tempFiles;
        this.messages = messages;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.enrichment = enrichment;
        this.metrics = metrics == null ? NoopExportMetrics.INSTANCE : metrics;
        this.prefetchExecutor = prefetchExecutor;
    }

    /**
     * Executes one attempt.
     *
     * @param plan         everything already decided: what to read, what to write, what it may cost
     * @param cancellation asked once per window
     * @param <P>          the definition's parameter type
     * @param <R>          the row type
     * @return what was produced
     */
    public <P extends ReportParameters, R> ReportRunResult execute(ExecutionPlan<P, R> plan,
                                                                   RunCancellation cancellation) {
        return new ReportRunAttempt<>(plan, cancellation == null ? RunCancellation.NEVER : cancellation,
                new EngineServices(factories, sink, tempFiles, messages, clock, enrichment, metrics,
                        prefetchExecutor)).run();
    }

    /** The formats this engine can write, for a planner deciding what a request may ask for. */
    public Map<String, ReportWriterFactory> writerFactories() {
        return factories;
    }

    private static Map<String, ReportWriterFactory> index(List<ReportWriterFactory> factories) {
        if (factories == null) {
            return Map.of();
        }
        Map<String, ReportWriterFactory> byId = new LinkedHashMap<>();
        for (ReportWriterFactory factory : factories) {
            ReportWriterFactory existing = byId.putIfAbsent(factory.format().id(), factory);
            if (existing != null) {
                throw new ExportConfigurationException(
                        "Two ReportWriterFactory beans claim format '" + factory.format().id() + "': "
                                + existing.getClass().getName() + " and " + factory.getClass().getName()
                                + "; which one wrote a file would depend on bean ordering");
            }
        }
        return Map.copyOf(byId);
    }
}
