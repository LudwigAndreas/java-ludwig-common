package ru.ludwigandreas.export.lifecycle;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.export.api.ExportAuditEvent;
import ru.ludwigandreas.export.api.ExportAuditSink;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.ReportRequest;
import ru.ludwigandreas.export.api.RunStatus;
import ru.ludwigandreas.export.api.SortKey;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.engine.DefaultExecutionPlanner;
import ru.ludwigandreas.export.engine.ExecutionPlan;
import ru.ludwigandreas.export.engine.ExecutionPlanner;
import ru.ludwigandreas.export.engine.ReportRunEngine;
import ru.ludwigandreas.export.engine.ReportRunResult;
import ru.ludwigandreas.export.engine.RunCancellation;
import ru.ludwigandreas.export.entity.ExportReportRun;
import ru.ludwigandreas.export.exception.ReportCancelledException;
import ru.ludwigandreas.export.metrics.ExportMetrics;
import ru.ludwigandreas.export.registry.ReportWriterFactories;
import ru.ludwigandreas.job.core.claim.JobInstanceIdentity;
import ru.ludwigandreas.export.repository.ExportReportRunRepository;

/**
 * Executes one claimed run: re-plan, heartbeat, run the engine, record the outcome.
 *
 * <h2>Re-planning is the security control, not an optimisation</h2>
 *
 * <p>A deferred run plans again when it starts, from the requester's authorities <em>as they are
 * now</em> rather than from the snapshot taken when the request was accepted. That is what makes a
 * revocation take effect: {@code identity-projection} evicts the authority cache on change precisely
 * so it happens in milliseconds, and a reporting engine that trusted a snapshot would be the one path
 * in the estate where a revoked permission still produced data - and the highest-volume one, since a
 * report is a bulk read by definition.
 *
 * <p>The re-plan can therefore fail, with a 403 rather than a 500, and that failure is terminal
 * rather than retried: attempting it again in thirty seconds would not give the authority back.
 *
 * <h2>The heartbeat is what makes the lease mean anything</h2>
 *
 * <p>While the engine runs, a scheduled task renews the lease. A renewal that comes back false means
 * another instance has already reclaimed this run, so this one stops immediately rather than finishing
 * the file - two instances writing the same report would produce two files under one run id, and the
 * second write would overwrite the first's outputs.
 */
@Slf4j
public class ExportRunExecutor {

    /** Nanoseconds in a millisecond, which is the unit the run timer records in. */
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final ExecutionPlanner planner;
    private final ReportRunEngine engine;
    private final ExportRunService lifecycle;
    private final ExportReportRunRepository runs;
    private final ExportAuditSink audit;
    private final ExportMetrics metrics;
    private final ExportProperties properties;
    private final JobInstanceIdentity identity;
    private final Clock clock;
    private final ScheduledExecutorService heartbeats;
    private final ReportWriterFactories formats;

    // SUPPRESS CHECKSTYLE ParameterNumber - a Spring bean assembled in one @Bean method, where every
    // argument is named by its own bean; there is no positional call site for the rule to protect.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ExportRunExecutor(ExecutionPlanner planner, ReportRunEngine engine,
                             ExportRunService lifecycle, ExportReportRunRepository runs,
                             ExportAuditSink audit, ExportMetrics metrics, ExportProperties properties,
                             JobInstanceIdentity identity, Clock clock,
                             ScheduledExecutorService heartbeats, ReportWriterFactories formats) {
        this.planner = planner;
        this.engine = engine;
        this.lifecycle = lifecycle;
        this.runs = runs;
        this.audit = audit;
        this.metrics = metrics;
        this.properties = properties;
        this.identity = identity;
        this.clock = clock;
        this.heartbeats = heartbeats;
        this.formats = formats;
    }

    /**
     * Runs one claimed row to a terminal state.
     *
     * <p>Never throws. The poller's worker has nowhere to report to, and an exception escaping here
     * would leave the run leased until it expired rather than recorded as failed - turning a
     * reportable failure into a five-minute silence.
     */
    public void execute(ExportReportRun run) {
        UUID runId = run.getId();
        long started = System.nanoTime();
        AtomicBoolean superseded = new AtomicBoolean();
        ScheduledFuture<?> heartbeat = startHeartbeat(runId, superseded);
        try {
            ReportRunResult result = runEngine(run, superseded);
            lifecycle.recordSuccess(runId, result);
            result.outputs().forEach(output -> metrics.outputStored(run.getDefinitionKey(),
                    output.formatId(), output.stored().sizeBytes()));
            recordFinished(run, RunStatus.SUCCEEDED, result.rowsWritten(), result.degradedStages(),
                    result.outputs().stream().map(output -> output.stored().uri()).toList(), started);
        } catch (ReportCancelledException e) {
            lifecycle.recordCancelled(runId, e.getRowsWritten());
            recordFinished(run, RunStatus.CANCELLED, e.getRowsWritten(), List.of(), List.of(), started);
        } catch (RuntimeException e) {
            lifecycle.recordFailure(runId, e);
            recordFinished(run, RunStatus.FAILED, 0, List.of(), List.of(), started);
        } finally {
            heartbeat.cancel(false);
        }
    }

    private ReportRunResult runEngine(ExportReportRun run, AtomicBoolean superseded) {
        Set<String> authorities = currentAuthorities(run);
        // false: this is a poller thread. There is no requester's token in scope here and there never
        // will be, so a stage that needs one is refused by the planner rather than called anonymously or
        // under this service's own credentials.
        ExecutionPlan<?, ?> plan = planner.plan(run.getId(), toRequest(run), run.getRequester(),
                authorities, false);
        return engine.execute(cast(plan), cancellation(run.getId(), superseded));
    }

    /**
     * Asked once per window, by both halves of the pipeline.
     *
     * <p>Three things can stop a run and all of them arrive here: somebody cancelled it, the lease
     * was lost to another instance, or the row is gone. Folding them into one signal means the engine
     * has one way to stop rather than three, and every one of them unwinds through the same path that
     * closes the stream, closes the writers and deletes the temp files.
     */
    private RunCancellation cancellation(UUID runId, AtomicBoolean superseded) {
        return () -> superseded.get() || lifecycle.isCancellationRequested(runId);
    }

    private ScheduledFuture<?> startHeartbeat(UUID runId, AtomicBoolean superseded) {
        Duration interval = properties.getPoller().getHeartbeatInterval();
        return heartbeats.scheduleAtFixedRate(() -> {
            boolean stillOurs = runs.renewLease(runId, identity.owner(), clock.instant(),
                    properties.getPoller().getLeaseDuration());
            if (!stillOurs) {
                log.warn("Lost the lease on report run {}; another instance has taken it, so this one"
                        + " stops at the next window", runId);
                superseded.set(true);
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * The authorities the run executes under.
     *
     * <p>Read now, not from {@code principal_snapshot}. The snapshot says who asked; this says what
     * they may currently see. Re-resolving is deliberately left to the planner's scope resolver and
     * the caller's authority source rather than being cached on the run - see the class comment.
     */
    private Set<String> currentAuthorities(ExportReportRun run) {
        Map<String, String> snapshot = run.getPrincipalSnapshot();
        if (snapshot == null || snapshot.get("authorities") == null) {
            return Set.of();
        }
        return Set.of(snapshot.get("authorities").split(","));
    }

    private ReportRequest toRequest(ExportReportRun run) {
        List<ReportFormat> requested = run.getFormats().stream()
                .map(formats::require)
                .toList();
        return new ReportRequest(run.getDefinitionKey(), run.getParameters(), run.getColumnIds(),
                run.getFilterExpression(), List.<SortKey>of(), requested, Map.of(),
                Locale.forLanguageTag(run.getLocale()), DefaultExecutionPlanner.zoneOf(run.getTimeZone()),
                run.getSavedReportId(), run.getIdempotencyKey());
    }

    private void recordFinished(ExportReportRun run, RunStatus status, long rows,
                                List<String> degraded, List<String> uris, long startedNanos) {
        long millis = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI;
        metrics.runFinished(run.getDefinitionKey(), status.name(), rows, millis);
        audit.record(new ExportAuditEvent(run.getId(), status, clock.instant(),
                run.getDefinitionKey(), run.getSavedReportId(), run.getRequester(),
                run.getParameters(), run.getColumnIds(), run.getFormats(), rows, uris, degraded,
                run.getCorrelationId()));
    }

    @SuppressWarnings("unchecked")
    private static <P extends ru.ludwigandreas.export.api.ReportParameters, R>
            ExecutionPlan<P, R> cast(ExecutionPlan<?, ?> plan) {
        return (ExecutionPlan<P, R>) plan;
    }
}
