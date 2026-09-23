package ru.ludwigandreas.reconciliation.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.reconciliation.api.DemandRequest;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.api.ExternalStamp;
import ru.ludwigandreas.reconciliation.api.FetchOutcome;
import ru.ludwigandreas.reconciliation.audit.AuditEvent;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditLogger;
import ru.ludwigandreas.reconciliation.config.ProcessingMode;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.metrics.RunOutcome;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One run of one task's hot or cold pass: take the lock, ask for demand, fetch it, write it down.
 *
 * <p>Stage 1, 2 and the plumbing of 5 from the five-stage model, in one place. Stage 3 is the
 * {@link FetchWalker}, stage 4 is the task's reconciler, and the accounting in stage 5 is the
 * {@link StagingService} and the {@link ApplyService}.
 */
public class TaskRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

    private final RunLock runLock;
    private final FetchWalker walker;
    private final StagingService staging;
    private final DirectApplyService directApply;
    private final TaskStateService taskState;
    private final SyncInboxRecordRepository repository;
    private final ReconciliationMetrics metrics;
    private final ReconciliationAuditLogger auditLogger;
    private final CorrelationIdSource correlationIds;

    /**
     * Creates the runner.
     *
     * @param runLock        cluster-wide exclusion, so N replicas produce one run
     * @param walker         the fetch shapes
     * @param staging        writes fetch outcomes down
     * @param directApply    applies them inline instead, under {@code mode: direct}
     * @param taskState      cursor and watermark
     * @param repository     the staging table, for the suppression filter
     * @param metrics        instrumentation
     * @param auditLogger    the audit trail
     * @param correlationIds correlation id for the run
     */
    public TaskRunner(RunLock runLock,
                      FetchWalker walker,
                      StagingService staging,
                      DirectApplyService directApply,
                      TaskStateService taskState,
                      SyncInboxRecordRepository repository,
                      ReconciliationMetrics metrics,
                      ReconciliationAuditLogger auditLogger,
                      CorrelationIdSource correlationIds) {
        this.runLock = runLock;
        this.walker = walker;
        this.staging = staging;
        this.directApply = directApply;
        this.taskState = taskState;
        this.repository = repository;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
        this.correlationIds = correlationIds;
    }

    /**
     * Runs one pass.
     *
     * @param task the task
     * @param tier which half of demand
     * @return how many rows the run produced
     */
    public int run(RegisteredTask<?, ?, ?> task, DemandTier tier) {
        TaskSettings settings = task.settings();
        String lockName = "reconciliation:" + settings.name() + ":" + tier.name().toLowerCase(java.util.Locale.ROOT);

        // The lease is the run timeout: a run that outlives it has, by the task's own declaration,
        // stopped being the authoritative one, and another instance is entitled to take over.
        var acquired = runLock.tryAcquire(lockName, settings.runTimeout());
        if (acquired.isEmpty()) {
            metrics.recordRun(settings.name(), tier.name().toLowerCase(java.util.Locale.ROOT),
                    RunOutcome.SKIPPED_LOCKED, Duration.ZERO);
            log.debug("Task '{}' ({}) is already running on another instance", settings.name(), tier);
            return 0;
        }

        try (RunLockHandle lease = acquired.get();
             CorrelationIdSource.Scope scope = correlationIds.open()) {
            return runHolding(task, tier, lease, scope.correlationId());
        }
    }

    private int runHolding(RegisteredTask<?, ?, ?> task,
                           DemandTier tier,
                           RunLockHandle lease,
                           String correlationId) {
        TaskSettings settings = task.settings();
        Instant started = Instant.now();
        RunContext context = new RunContext(settings.name(), tier, lease.runId(), correlationId,
                started, started.plus(settings.runTimeout()));
        audit(settings, "run.started", context, null);

        RunOutcome outcome = RunOutcome.SUCCESS;
        int produced = 0;
        try {
            produced = execute(task, context);
            if (context.isExpired()) {
                outcome = RunOutcome.TIMED_OUT;
            }
        } catch (Exception e) {
            // A run is a framework boundary. Whatever it managed to stage before failing is already
            // committed and will be applied - which is the point of staging - so the failure is
            // recorded and the schedule survives it.
            outcome = RunOutcome.FAILURE;
            log.error("Task '{}' ({}) run failed", settings.name(), tier, e);
            audit(settings, "run.failed", context, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            metrics.recordRun(settings.name(), context.tierTag(), outcome,
                    Duration.between(started, Instant.now()));
        }
        audit(settings, "run.finished", context, outcome + ", " + produced + " row(s)");
        return produced;
    }

    /** Captures the task's wildcards so the whole run below is fully typed. */
    private <I, K, O> int execute(RegisteredTask<I, K, O> task, RunContext context) {
        TaskSettings settings = task.settings();
        DemandRequest request = new DemandRequest(settings.name(), context.tier(),
                settings.demand().incremental()
                        ? taskState.watermark(settings.name(), context.tier()).orElse(null) : null,
                settings.demand().maxRecordsPerRun(), context.runId());

        List<I> demand = task.task().demand().demand(request);
        Set<K> keys = keysOf(task, demand, context);

        // The highest external timestamp this run actually staged, which is what the watermark may
        // advance to. Tracked from the outcomes rather than from the demand, because a record the
        // partner did not return has not been seen and must not move the watermark past itself.
        AtomicReference<Instant> highWater = new AtomicReference<>();

        FetchSink<K, O> sink = outcomes -> {
            outcomes.stream()
                    .filter(FetchOutcome.Found.class::isInstance)
                    .map(o -> task.task().stampOf(((FetchOutcome.Found<K, O>) o).record()))
                    .map(ExternalStamp::timestamp)
                    .filter(java.util.Objects::nonNull)
                    .forEach(timestamp -> highWater.accumulateAndGet(timestamp,
                            (current, candidate) -> current == null || candidate.isAfter(current)
                                    ? candidate : current));
            return settings.mode() == ProcessingMode.DIRECT
                    ? directApply.apply(task, outcomes, context)
                    : staging.stage(task, outcomes, context, null);
        };

        int produced = walker.walk(task, keys, context, sink);
        taskState.completeRun(settings.name(), context.tier(), highWater.get(), context.runId());
        return produced;
    }

    /**
     * Turns demand into correlation keys, dropping the ones this run must leave alone.
     *
     * <p>Suppression is read once per run rather than per key: a key whose fetch keeps failing is
     * skipped until its backoff elapses, and a key whose budget is spent is skipped until an operator
     * requeues it. Without this, a key the partner reliably chokes on is refetched on every run
     * forever, at the task's cadence, indistinguishable in the metrics from work making progress.
     */
    private <I, K, O> Set<K> keysOf(RegisteredTask<I, K, O> task, List<I> demand, RunContext context) {
        Set<String> suppressed = new HashSet<>(
                repository.findSuppressedKeys(task.settings().name(), Instant.now()));
        Set<K> keys = new LinkedHashSet<>();
        int skipped = 0;
        for (I local : demand) {
            K key = task.task().localKey().apply(local);
            if (suppressed.contains(task.task().keyCodec().encode(key))) {
                skipped++;
                continue;
            }
            keys.add(key);
        }
        if (skipped > 0) {
            log.debug("Task '{}' skipped {} key(s) with an outstanding fetch failure",
                    task.settings().name(), skipped);
        }
        return keys;
    }

    private void audit(TaskSettings settings, String event, RunContext context, String detail) {
        if (!settings.audit().enabled()) {
            return;
        }
        auditLogger.record(AuditEvent.builder(settings.name(), AuditEvent.Category.RUN, event)
                .subject(context.tierTag())
                .detail(detail)
                .runId(context.runId())
                .correlationId(context.correlationId())
                .build());
    }

    /** The run id this task's next run will be identified by, for tests and the actuator. */
    static UUID newRunId() {
        return UUID.randomUUID();
    }
}
