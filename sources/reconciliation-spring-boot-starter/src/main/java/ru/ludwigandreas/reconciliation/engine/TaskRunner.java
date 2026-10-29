package ru.ludwigandreas.reconciliation.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.reconciliation.api.DemandRequest;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.api.ExternalStamp;
import ru.ludwigandreas.reconciliation.api.FetchOutcome;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent.Category;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.reconciliation.config.ProcessingMode;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.entity.RunKind;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.metrics.RunOutcome;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    /** How many keys a dry run lists per outcome kind before the report stops being a report. */
    private static final int SAMPLE_SIZE = 20;

    private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

    private final RunLock runLock;
    private final FetchWalker walker;
    private final StagingService staging;
    private final DirectApplyService directApply;
    private final TaskStateService taskState;
    private final SyncInboxRecordRepository repository;
    private final ReconciliationMetrics metrics;
    private final AuditSink auditLogger;
    private final CorrelationIdSource correlationIds;
    private final RunRecorder recorder;

    /**
     * Where operator-triggered runs execute. Not the fetch executor: a run fans its fetches out onto that one, so a
     * run occupying a fetch thread would wait for fetch threads.
     */
    private final TaskExecutor manualRuns;

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
     * @param recorder       the run-history row
     * @param manualRuns     where operator-triggered runs execute; bounded, and refusing rather than queueing
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public TaskRunner(RunLock runLock,
                      FetchWalker walker,
                      StagingService staging,
                      DirectApplyService directApply,
                      TaskStateService taskState,
                      SyncInboxRecordRepository repository,
                      ReconciliationMetrics metrics,
                      AuditSink auditLogger,
                      CorrelationIdSource correlationIds,
                      RunRecorder recorder,
                      TaskExecutor manualRuns) {
        this.runLock = runLock;
        this.walker = walker;
        this.staging = staging;
        this.directApply = directApply;
        this.taskState = taskState;
        this.repository = repository;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
        this.correlationIds = correlationIds;
        this.recorder = recorder;
        this.manualRuns = manualRuns;
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
        // The lease is the run timeout: a run that outlives it has, by the task's own declaration,
        // stopped being the authoritative one, and another instance is entitled to take over.
        var acquired = runLock.tryAcquire(lockName(settings.name(), tier), settings.runTimeout());
        if (acquired.isEmpty()) {
            metrics.recordRun(settings.name(), tier.name().toLowerCase(Locale.ROOT),
                    RunOutcome.SKIPPED_LOCKED, Duration.ZERO);
            log.debug("Task '{}' ({}) is already running on another instance", settings.name(), tier);
            return 0;
        }

        try (RunLockHandle lease = acquired.get();
             CorrelationIdSource.Scope scope = correlationIds.open()) {
            return runHolding(task, tier, lease, scope.correlationId(), RunKind.SCHEDULED);
        }
    }

    private int runHolding(RegisteredTask<?, ?, ?> task,
                           DemandTier tier,
                           RunLockHandle lease,
                           String correlationId,
                           RunKind kind) {
        TaskSettings settings = task.settings();
        Instant started = Instant.now();
        RunContext context = new RunContext(settings.name(), tier, lease.runId(), correlationId,
                started, started.plus(settings.runTimeout()));
        recorder.started(context.runId(), settings.name(), tier, kind, lease.owner(), correlationId);
        audit(settings, "run.started", context, null);

        RunOutcome outcome = RunOutcome.SUCCESS;
        RunTally tally = new RunTally();
        int produced = 0;
        try {
            produced = execute(task, context, tally);
            tally.setProduced(produced);
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
        recorder.finished(context.runId(), settings.name(), outcome, failureCodeOf(outcome), tally, null);
        return produced;
    }

    private static String failureCodeOf(RunOutcome outcome) {
        return switch (outcome) {
            case FAILURE -> RunFailureCodes.RUN_FAILED;
            case TIMED_OUT -> RunFailureCodes.RUN_TIMED_OUT;
            case SUCCESS, SKIPPED_LOCKED -> null;
        };
    }

    /**
     * Starts an operator's run, dry run or backfill and returns at once.
     *
     * <p>The lease is taken here, on the caller's thread, so "a run is already in progress on some replica" is
     * answered exactly and immediately; the pass then runs on the manual-run executor and owns the lease from there.
     * The run is recorded as pending before it is handed over, so its status resource exists before the caller is
     * told where it is.
     *
     * @param task        the task
     * @param tier        which half of demand; a backfill always runs on the hot lease
     * @param kind        {@link RunKind#MANUAL}, {@link RunKind#DRY_RUN} or {@link RunKind#BACKFILL}
     * @param keys        a backfill's keys in the task codec's form; ignored otherwise
     * @param triggeredBy who asked
     * @return the run id, or empty when the lease is held elsewhere
     * @throws ManualRunCapacityException when every manual-run slot is busy; the lease is released again
     */
    public Optional<UUID> start(RegisteredTask<?, ?, ?> task, DemandTier tier, RunKind kind, List<String> keys,
                                String triggeredBy) {
        TaskSettings settings = task.settings();
        DemandTier leaseTier = kind == RunKind.BACKFILL ? DemandTier.HOT : tier;
        var acquired = runLock.tryAcquire(lockName(settings.name(), leaseTier), settings.runTimeout());
        if (acquired.isEmpty()) {
            return Optional.empty();
        }
        RunLockHandle lease = acquired.get();
        UUID runId = lease.runId();
        recorder.submitted(runId, settings.name(), leaseTier, kind, triggeredBy, lease.owner());
        try {
            manualRuns.execute(() -> runManual(task, leaseTier, kind, keys, lease));
        } catch (TaskRejectedException full) {
            recorder.discard(runId, settings.name());
            lease.close();
            throw new ManualRunCapacityException(settings.name());
        }
        return Optional.of(runId);
    }

    private void runManual(RegisteredTask<?, ?, ?> task, DemandTier tier, RunKind kind, List<String> keys,
                           RunLockHandle lease) {
        try (RunLockHandle held = lease;
             CorrelationIdSource.Scope scope = correlationIds.open()) {
            switch (kind) {
                case DRY_RUN -> dryRunHolding(task, tier, held, scope.correlationId());
                case BACKFILL -> backfillHolding(task, keys, held, scope.correlationId());
                default -> runHolding(task, tier, held, scope.correlationId(), RunKind.MANUAL);
            }
        } catch (RuntimeException e) {
            // CHECKSTYLE.OFF: IllegalCatch - this is the top of a pooled thread: whatever escaped has already been
            // recorded on the run, and letting it out would only end up in the executor's default handler.
            log.error("Manual {} of task '{}' failed", kind, task.settings().name(), e);
            // CHECKSTYLE.ON: IllegalCatch
        }
    }

    /** Captures the task's wildcards so the whole run below is fully typed. */
    private <I, K, O> int execute(RegisteredTask<I, K, O> task, RunContext context, RunTally tally) {
        TaskSettings settings = task.settings();
        DemandRequest request = new DemandRequest(settings.name(), context.tier(),
                settings.demand().incremental()
                        ? taskState.watermark(settings.name(), context.tier()).orElse(null) : null,
                settings.demand().maxRecordsPerRun(), context.runId());

        List<I> demand = task.task().demand().demand(request);
        Set<K> keys = keysOf(task, demand, context);
        tally.setDemanded(keys.size());

        // The highest external timestamp this run actually staged, which is what the watermark may
        // advance to. Tracked from the outcomes rather than from the demand, because a record the
        // partner did not return has not been seen and must not move the watermark past itself.
        AtomicReference<Instant> highWater = new AtomicReference<>();

        FetchSink<K, O> sink = outcomes -> {
            tally.addFetched((int) outcomes.stream().filter(FetchOutcome.Found.class::isInstance).count());
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
     * Fetches what a run would fetch and reports it, staging nothing.
     *
     * <p>What an operator reaches for before turning a new task on, or when one is behaving oddly:
     * it answers "what does the partner actually say about my demand right now?" without staging a
     * row, applying a record or moving a watermark. It does take the run lease, because a dry run
     * that raced a real one would report a partner interaction that the real run also made.
     *
     * <p>Started through {@link #start} with {@link RunKind#DRY_RUN}; the report becomes the run's result.
     */
    private void dryRunHolding(RegisteredTask<?, ?, ?> task, DemandTier tier, RunLockHandle lease,
                               String correlationId) {
        TaskSettings settings = task.settings();
        Instant started = Instant.now();
        RunContext context = new RunContext(settings.name(), tier, lease.runId(),
                correlationId, started, started.plus(settings.runTimeout()));
        recorder.started(context.runId(), settings.name(), tier, RunKind.DRY_RUN, lease.owner(), correlationId);
        audit(settings, "run.dry", context, null);
        RunTally tally = new RunTally();
        try {
            Map<String, Object> report = inspect(task, context);
            tally.setDemanded(((Number) report.get("demandSize")).intValue());
            recorder.finished(context.runId(), settings.name(), RunOutcome.SUCCESS, null, tally, report);
        } catch (RuntimeException e) {
            // CHECKSTYLE.OFF: IllegalCatch - recorded on the run, then rethrown to the pass's own boundary.
            recorder.finished(context.runId(), settings.name(), RunOutcome.FAILURE, RunFailureCodes.RUN_FAILED,
                    tally, null);
            throw e;
            // CHECKSTYLE.ON: IllegalCatch
        }
    }

    private <I, K, O> Map<String, Object> inspect(RegisteredTask<I, K, O> task, RunContext context) {
        TaskSettings settings = task.settings();
        DemandRequest request = new DemandRequest(settings.name(), context.tier(), null,
                settings.demand().maxRecordsPerRun(), context.runId());
        Set<K> keys = keysOf(task, task.task().demand().demand(request), context);

        Map<String, List<String>> samples = new LinkedHashMap<>();
        FetchSink<K, O> sink = outcomes -> {
            outcomes.forEach(outcome -> samples
                    .computeIfAbsent(kindOf(outcome), kind -> new ArrayList<>())
                    .add(task.task().keyCodec().encode(outcome.key())));
            return 0;
        };
        walker.walk(task, keys, context, sink);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ran", true);
        report.put("runId", context.runId());
        report.put("demandSize", keys.size());
        samples.forEach((kind, matched) -> report.put(kind, Map.of(
                "count", matched.size(),
                "sample", matched.stream().limit(SAMPLE_SIZE).toList())));
        report.put("note", "nothing was staged, applied or checkpointed");
        return report;
    }

    private static <K, O> String kindOf(FetchOutcome<K, O> outcome) {
        if (outcome instanceof FetchOutcome.Found) {
            return "found";
        }
        return outcome instanceof FetchOutcome.NotFound ? "notFound" : "failed";
    }

    /**
     * Fetches an explicit set of correlation keys and stages the results, bypassing the demand query.
     *
     * <p>The repair tool for the case the demand query cannot express: a range of records that were
     * missed while an integration was switched off, or a set of ids a partner has asked to be
     * re-synchronised. It goes through exactly the same walker, staging and apply path as a scheduled
     * run, so a backfilled record is indistinguishable from a normally fetched one - including its
     * stale-write and idempotency guards, which is what stops a backfill from overwriting newer state.
     *
     * <p>Started through {@link #start} with {@link RunKind#BACKFILL}; the requested and staged counts become the
     * run's result.
     */
    private void backfillHolding(RegisteredTask<?, ?, ?> task, List<String> encodedKeys, RunLockHandle lease,
                                 String correlationId) {
        TaskSettings settings = task.settings();
        Instant started = Instant.now();
        RunContext context = new RunContext(settings.name(), DemandTier.HOT, lease.runId(),
                correlationId, started, started.plus(settings.runTimeout()));
        recorder.started(context.runId(), settings.name(), DemandTier.HOT, RunKind.BACKFILL, lease.owner(),
                correlationId);
        audit(settings, "run.backfill", context, encodedKeys.size() + " key(s)");
        RunTally tally = new RunTally();
        tally.setDemanded(encodedKeys.size());
        try {
            int staged = backfillTyped(task, encodedKeys, context, tally);
            tally.setProduced(staged);
            recorder.finished(context.runId(), settings.name(), RunOutcome.SUCCESS, null, tally,
                    Map.of("requested", encodedKeys.size(), "staged", staged));
        } catch (RuntimeException e) {
            // CHECKSTYLE.OFF: IllegalCatch - recorded on the run, then rethrown to the pass's own boundary.
            recorder.finished(context.runId(), settings.name(), RunOutcome.FAILURE, RunFailureCodes.RUN_FAILED,
                    tally, null);
            throw e;
            // CHECKSTYLE.ON: IllegalCatch
        }
    }

    private <I, K, O> int backfillTyped(RegisteredTask<I, K, O> task, List<String> encodedKeys, RunContext context,
                                        RunTally tally) {
        TaskSettings settings = task.settings();
        Set<K> keys = new LinkedHashSet<>();
        encodedKeys.forEach(key -> keys.add(task.task().keyCodec().decode(key)));
        FetchSink<K, O> sink = outcomes -> {
            tally.addFetched((int) outcomes.stream().filter(FetchOutcome.Found.class::isInstance).count());
            return settings.mode() == ProcessingMode.DIRECT
                    ? directApply.apply(task, outcomes, context)
                    : staging.stage(task, outcomes, context, null);
        };

        // The watermark is deliberately not advanced: a backfill visits a chosen set of keys, not
        // everything that changed since a point in time, so treating it as a completed sweep would
        // move the watermark past records it never looked at.
        return walker.walk(task, keys, context, sink);
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
        auditLogger.record(ReconciliationAuditEvent.builder(settings.name(), Category.RUN, event)
                .subject(context.tierTag())
                .detail(detail)
                .runId(context.runId())
                .correlationId(context.correlationId())
                .build());
    }

    private static String lockName(String taskName, DemandTier tier) {
        return "reconciliation:" + taskName + ":" + tier.name().toLowerCase(Locale.ROOT);
    }
}
