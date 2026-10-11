package ru.ludwigandreas.reconciliation.engine.enumeration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent.Category;
import ru.ludwigandreas.reconciliation.config.EnumerationSettings;
import ru.ludwigandreas.reconciliation.engine.BoundedFanOut;
import ru.ludwigandreas.reconciliation.engine.CorrelationIdSource;
import ru.ludwigandreas.reconciliation.entity.ScopeMemberStatus;
import ru.ludwigandreas.reconciliation.entity.SyncScopeState;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.metrics.RunOutcome;
import ru.ludwigandreas.reconciliation.repository.SyncScopeMemberRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

/**
 * One run of one enumeration's discover or walk pass, and the operator's ways of driving a scope by
 * hand.
 *
 * <p>Everything that touches a scope's walk state does so holding the task's walk lease, the scheduled
 * pass and the operator's actions alike. That is the whole concurrency model: with at most one holder
 * per task there is at most one walk per scope, so a membership row never needs a version column and
 * a cursor is never advanced by two writers.
 */
public class EnumerationRunner {

    private static final String PASS_WALK = "walk";
    private static final String PASS_DISCOVER = "discover";

    private static final Logger log = LoggerFactory.getLogger(EnumerationRunner.class);

    private final RunLock runLock;
    private final ScopeDiscoveryService discovery;
    private final ScopeStateService states;
    private final ScopeWalker walker;
    private final SweepService sweeps;
    private final SyncScopeMemberRepository members;
    private final Executor executor;
    private final ReconciliationMetrics metrics;
    private final AuditSink auditSink;
    private final CorrelationIdSource correlationIds;

    /**
     * Creates the runner.
     *
     * @param runLock        the per-task run lease
     * @param discovery      registers new scopes
     * @param states         scope state
     * @param walker         walks one scope
     * @param sweeps         reports members a full walk did not see
     * @param members        the membership table
     * @param executor       the shared fetch executor
     * @param metrics        instrumentation
     * @param auditSink      the audit trail
     * @param correlationIds correlation id for the run
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public EnumerationRunner(RunLock runLock,
                             ScopeDiscoveryService discovery,
                             ScopeStateService states,
                             ScopeWalker walker,
                             SweepService sweeps,
                             SyncScopeMemberRepository members,
                             Executor executor,
                             ReconciliationMetrics metrics,
                             AuditSink auditSink,
                             CorrelationIdSource correlationIds) {
        this.runLock = runLock;
        this.discovery = discovery;
        this.states = states;
        this.walker = walker;
        this.sweeps = sweeps;
        this.members = members;
        this.executor = executor;
        this.metrics = metrics;
        this.auditSink = auditSink;
        this.correlationIds = correlationIds;
    }

    /**
     * Registers the scopes the enumeration has not seen before.
     *
     * @param enumeration the enumeration
     * @return how many scopes were new; zero when another instance holds the lease
     */
    public int discoverPass(RegisteredEnumeration<?, ?, ?, ?> enumeration) {
        return holding(enumeration.settings(), PASS_DISCOVER, 0,
                (lease, deadline) -> discovery.discover(enumeration));
    }

    /**
     * Walks the scopes that are due.
     *
     * @param enumeration the enumeration
     * @return how many scopes were walked; zero when another instance holds the lease
     */
    public int walkPass(RegisteredEnumeration<?, ?, ?, ?> enumeration) {
        return walkDue(enumeration).size();
    }

    /**
     * Walks the scopes that are due and says what each walk did.
     *
     * @param enumeration the enumeration
     * @return one report per scope walked; empty when another instance holds the lease
     */
    public List<WalkReport> walkDue(RegisteredEnumeration<?, ?, ?, ?> enumeration) {
        return holding(enumeration.settings(), PASS_WALK, List.<WalkReport>of(),
                (lease, deadline) -> walkDueTyped(enumeration, lease.runId(), deadline));
    }

    private <S, SK, M, MK> List<WalkReport> walkDueTyped(RegisteredEnumeration<S, SK, M, MK> enumeration,
                                                         UUID runId,
                                                         Instant deadline) {
        EnumerationSettings settings = enumeration.settings();
        List<SyncScopeState> due = states.due(settings.name(), settings.maxScopesPerRun());
        return BoundedFanOut.run(executor, settings.maxConcurrency(), due,
                state -> walker.walk(enumeration, state, runId, deadline),
                (state, error) -> {
                    // The walker turns a feed's and a reconciler's failures into a report itself, so
                    // what reaches here is the engine's own bookkeeping failing. The scope keeps
                    // whatever was committed and is simply picked up again.
                    log.error("Enumeration '{}' failed outside a page while walking scope {}",
                            settings.name(), state.getScopeKey(), error);
                    return new WalkReport(state.getScopeKey(), WalkReport.Status.FAILED, null,
                            PageOutcome.NONE, null, String.valueOf(error));
                });
    }

    /**
     * Walks one scope now, whether or not it is due - an operator action.
     *
     * @param enumeration the enumeration
     * @param scopeKey    the encoded scope key
     * @param full        whether to force a full walk rather than let the planner choose
     * @return the walk's report, or empty when a run is in progress on another instance
     */
    public Optional<WalkReport> walkScope(RegisteredEnumeration<?, ?, ?, ?> enumeration,
                                          String scopeKey,
                                          boolean full) {
        return holding(enumeration.settings(), PASS_WALK, Optional.<WalkReport>empty(),
                (lease, deadline) -> Optional.of(
                        walkScopeTyped(enumeration, scopeKey, full, lease.runId(), deadline)));
    }

    private <S, SK, M, MK> WalkReport walkScopeTyped(RegisteredEnumeration<S, SK, M, MK> enumeration,
                                                     String scopeKey,
                                                     boolean full,
                                                     UUID runId,
                                                     Instant deadline) {
        String name = enumeration.name();
        // An operator may name a scope discovery has not reached yet; registering it here is what
        // the next discover pass would have done.
        states.register(name, List.of(scopeKey));
        SyncScopeState state = require(name, scopeKey);
        if (full && state.getWalkKind() == null) {
            states.forceFullWalk(state.getId(), false, false);
            state = require(name, scopeKey);
        }
        return walker.walk(enumeration, state, runId, deadline);
    }

    /**
     * Lists a scope in full and reports what a walk would do, writing nothing.
     *
     * @param enumeration the enumeration
     * @param scopeKey    the encoded scope key
     * @return the preview, or a note that a run is in progress
     */
    public Map<String, Object> preview(RegisteredEnumeration<?, ?, ?, ?> enumeration, String scopeKey) {
        return holding(enumeration.settings(), PASS_WALK,
                Map.<String, Object>of("ran", false,
                        "note", "a run is in progress on another instance; nothing was fetched"),
                (lease, deadline) -> walker.preview(enumeration, scopeKey, deadline));
    }

    /**
     * Performs a sweep that was withheld, on an operator's confirmation.
     *
     * @param enumeration the enumeration
     * @param scopeKey    the encoded scope key
     * @return what the sweep did, or empty when a run is in progress on another instance
     */
    public Optional<SweepService.Result> confirmSweep(RegisteredEnumeration<?, ?, ?, ?> enumeration,
                                                      String scopeKey) {
        return holding(enumeration.settings(), PASS_WALK, Optional.<SweepService.Result>empty(),
                (lease, deadline) -> Optional.of(confirmSweepTyped(enumeration, scopeKey, lease.runId())));
    }

    private <S, SK, M, MK> SweepService.Result confirmSweepTyped(
            RegisteredEnumeration<S, SK, M, MK> enumeration, String scopeKey, UUID runId) {
        SyncScopeState state = require(enumeration.name(), scopeKey);
        if (state.getSweepHeldAt() == null) {
            throw new IllegalStateException("Scope '" + scopeKey + "' of enumeration '"
                    + enumeration.name() + "' has no withheld sweep to confirm");
        }
        S scope = enumeration.task().scopes()
                .byKey(enumeration.task().scopeKeyCodec().decode(scopeKey))
                .orElseThrow(() -> new IllegalStateException("Scope '" + scopeKey + "' of enumeration '"
                        + enumeration.name() + "' has no local row; its next walk will forget it"));
        return sweeps.sweep(enumeration, state.getId(), scopeKey, scope, state.getGeneration(), runId, true);
    }

    /**
     * Drops a withheld sweep and makes the scope due for a full walk, so the question is asked of the
     * partner again rather than answered from a listing nobody trusted.
     *
     * @param enumeration the enumeration
     * @param scopeKey    the encoded scope key
     * @return false when a run is in progress on another instance
     */
    public boolean discardSweep(RegisteredEnumeration<?, ?, ?, ?> enumeration, String scopeKey) {
        return holding(enumeration.settings(), PASS_WALK, false, (lease, deadline) -> {
            SyncScopeState state = require(enumeration.name(), scopeKey);
            states.releaseSweep(state.getId());
            states.forceFullWalk(state.getId(), false, false);
            return true;
        });
    }

    /**
     * Clears a scope's cursor and watermark, keeping its membership, so its next walk is a full one.
     *
     * @param enumeration the enumeration
     * @param scopeKey    the encoded scope key
     * @return false when a run is in progress on another instance
     */
    public boolean resetScope(RegisteredEnumeration<?, ?, ?, ?> enumeration, String scopeKey) {
        return holding(enumeration.settings(), PASS_WALK, false, (lease, deadline) -> {
            states.forceFullWalk(require(enumeration.name(), scopeKey).getId(), true, true);
            return true;
        });
    }

    /**
     * Returns quarantined members to the queue with a fresh budget, and makes their scopes due for a
     * full walk - the only kind certain to list them again.
     *
     * @param enumeration the enumeration
     * @return how many members were requeued; -1 when a run is in progress on another instance
     */
    public long requeueMembers(RegisteredEnumeration<?, ?, ?, ?> enumeration) {
        String name = enumeration.name();
        return holding(enumeration.settings(), PASS_WALK, -1L, (lease, deadline) -> {
            Set<String> scopeKeys = members.findScopeKeysByStatus(name, ScopeMemberStatus.QUARANTINED);
            long requeued = members.requeueQuarantined(name, Instant.now());
            scopeKeys.forEach(scopeKey -> states.find(name, scopeKey)
                    .ifPresent(state -> states.forceFullWalk(state.getId(), false, false)));
            return requeued;
        });
    }

    private SyncScopeState require(String taskName, String scopeKey) {
        return states.find(taskName, scopeKey).orElseThrow(() -> new IllegalArgumentException(
                "Enumeration '" + taskName + "' has no scope '" + scopeKey + "'"));
    }

    /**
     * Runs {@code work} holding the task's lease for a pass, or returns {@code whenLocked}.
     *
     * <p>The lease TTL is the run timeout, and the deadline handed to the work is the same instant: a
     * run that outlives its lease has, by the task's own declaration, stopped being the authoritative
     * one.
     */
    private <T> T holding(EnumerationSettings settings, String pass, T whenLocked,
                          BiFunction<RunLockHandle, Instant, T> work) {
        var acquired = runLock.tryAcquire(lockName(settings.name(), pass), settings.runTimeout());
        if (acquired.isEmpty()) {
            metrics.recordRun(settings.name(), pass, RunOutcome.SKIPPED_LOCKED, Duration.ZERO);
            log.debug("Enumeration '{}' ({}) is already running on another instance", settings.name(), pass);
            return whenLocked;
        }
        Instant started = Instant.now();
        RunOutcome outcome = RunOutcome.SUCCESS;
        try (RunLockHandle lease = acquired.get();
             CorrelationIdSource.Scope scope = correlationIds.open()) {
            audit(settings, "run.started", pass, null, lease.runId(), scope.correlationId());
            T result = work.apply(lease, started.plus(settings.runTimeout()));
            audit(settings, "run.finished", pass, String.valueOf(result), lease.runId(), scope.correlationId());
            return result;
        } catch (RuntimeException failure) {
            outcome = RunOutcome.FAILURE;
            throw failure;
        } finally {
            metrics.recordRun(settings.name(), pass, outcome, Duration.between(started, Instant.now()));
        }
    }

    private void audit(EnumerationSettings settings, String event, String pass, String detail,
                       UUID runId, String correlationId) {
        if (!settings.auditEnabled()) {
            return;
        }
        auditSink.record(ReconciliationAuditEvent.builder(settings.name(), Category.RUN, event)
                .subject(pass)
                .detail(detail)
                .runId(runId)
                .correlationId(correlationId)
                .build());
    }

    static String lockName(String taskName, String pass) {
        return "reconciliation:" + taskName + ":" + pass;
    }
}
