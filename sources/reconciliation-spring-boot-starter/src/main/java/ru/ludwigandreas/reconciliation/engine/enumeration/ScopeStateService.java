package ru.ludwigandreas.reconciliation.engine.enumeration;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.job.core.backoff.BackoffCalculator;
import ru.ludwigandreas.reconciliation.config.EnumerationSettings;
import ru.ludwigandreas.reconciliation.entity.ScopeWalkKind;
import ru.ludwigandreas.reconciliation.entity.SyncScopeState;
import ru.ludwigandreas.reconciliation.repository.SyncScopeMemberRepository;
import ru.ludwigandreas.reconciliation.repository.SyncScopeStateRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reads and advances a scope's walk state.
 *
 * <p>Every write is its own transaction, for the reason {@code TaskStateService} gives: a checkpoint
 * has to survive the failure of the run that wrote it. The one exception is {@link #checkpoint}, which
 * is called from inside the transaction that applies a page so that the cursor and the page it
 * describes commit together or not at all.
 */
public class ScopeStateService {

    private final SyncScopeStateRepository states;
    private final SyncScopeMemberRepository members;
    private final TransactionTemplate transactions;

    /**
     * Creates the service.
     *
     * @param states             the scope state table
     * @param members            the membership table
     * @param transactionManager the transaction manager
     */
    public ScopeStateService(SyncScopeStateRepository states,
                             SyncScopeMemberRepository members,
                             PlatformTransactionManager transactionManager) {
        this.states = states;
        this.members = members;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** What a walk needs to know to start or resume. */
    public record WalkStart(ScopeWalkKind kind, String cursor, long generation, Instant watermark) {

        /**
         * The generation this walk stamps on the members it sees.
         *
         * <p>A full walk stamps the generation it will become when it completes; an incremental walk
         * stamps the last completed one, so it can never make a member look newer than the last full
         * listing that actually proved it was there.
         */
        public long stamp() {
            return kind == ScopeWalkKind.FULL ? generation + 1 : generation;
        }
    }

    /**
     * Registers scopes that are not yet known, due immediately.
     *
     * @param taskName  the task
     * @param scopeKeys encoded scope keys from one page of the scope source
     * @return how many were new
     */
    public int register(String taskName, Collection<String> scopeKeys) {
        if (scopeKeys.isEmpty()) {
            return 0;
        }
        return transactions.execute(status -> {
            Set<String> known = new HashSet<>();
            states.findScopes(taskName, scopeKeys).forEach(state -> known.add(state.getScopeKey()));
            Instant now = Instant.now();
            int created = 0;
            for (String scopeKey : new java.util.LinkedHashSet<>(scopeKeys)) {
                if (known.contains(scopeKey)) {
                    continue;
                }
                SyncScopeState state = new SyncScopeState();
                state.setTaskName(taskName);
                state.setScopeKey(scopeKey);
                state.setNextDueAt(now);
                state.setRegisteredAt(now);
                state.setUpdatedAt(now);
                states.save(state);
                created++;
            }
            return created;
        });
    }

    /**
     * The state of one scope.
     *
     * @param taskName the task
     * @param scopeKey the encoded scope key
     * @return the state, or empty if the scope is not registered
     */
    public Optional<SyncScopeState> find(String taskName, String scopeKey) {
        return states.findScope(taskName, scopeKey);
    }

    /**
     * The scopes a run should walk now.
     *
     * @param taskName the task
     * @param limit    the most scopes to take
     * @return the due scopes, never-walked first
     */
    public List<SyncScopeState> due(String taskName, int limit) {
        return states.findDue(taskName, Instant.now(), limit);
    }

    /**
     * Starts a walk, or picks up the one in progress.
     *
     * @param stateId the scope's state row
     * @param planned the kind to start if no walk is in progress
     * @param runId   the run doing the walking
     * @return what the walk needs to proceed
     */
    public WalkStart begin(UUID stateId, ScopeWalkKind planned, UUID runId) {
        return transactions.execute(status -> {
            SyncScopeState state = states.getByIdOrThrow(stateId);
            Instant now = Instant.now();
            if (state.getWalkKind() == null) {
                state.setWalkKind(planned);
                state.setWalkCursor(null);
                state.setWalkStartedAt(now);
                state.setHighWater(null);
            }
            state.setLastRunId(runId);
            state.setUpdatedAt(now);
            states.save(state);
            return new WalkStart(state.getWalkKind(), state.getWalkCursor(), state.getGeneration(),
                    state.getWatermark());
        });
    }

    /**
     * Records where a walk got to. Joins the caller's transaction on purpose - see the class comment.
     *
     * <p>The cursor is written with the page it describes, never before. The other order loses a page
     * on any crash between the two writes, and loses it silently.
     *
     * @param stateId   the scope's state row
     * @param cursor    the next page's token, or null after the last page
     * @param highWater the newest change timestamp the page carried, or null
     */
    void checkpoint(UUID stateId, String cursor, Instant highWater) {
        SyncScopeState state = states.getByIdOrThrow(stateId);
        state.setWalkCursor(cursor);
        if (highWater != null && (state.getHighWater() == null || highWater.isAfter(state.getHighWater()))) {
            state.setHighWater(highWater);
        }
        state.setUpdatedAt(Instant.now());
        states.save(state);
    }

    /**
     * Leaves a walk that ran out of time where it is, due again at once.
     *
     * @param stateId the scope's state row
     */
    public void interrupt(UUID stateId) {
        transactions.executeWithoutResult(status -> {
            SyncScopeState state = states.getByIdOrThrow(stateId);
            Instant now = Instant.now();
            state.setNextDueAt(now);
            state.setUpdatedAt(now);
            states.save(state);
        });
    }

    /**
     * Records that a page could not be fetched or applied. The walk stays in progress at the same
     * cursor and the scope backs off, so a partner that is down is not asked again on every run.
     *
     * @param stateId  the scope's state row
     * @param error    what went wrong
     * @param settings the task's settings, for the backoff
     */
    public void fail(UUID stateId, String error, EnumerationSettings settings) {
        transactions.executeWithoutResult(status -> {
            SyncScopeState state = states.getByIdOrThrow(stateId);
            Instant now = Instant.now();
            state.setWalkAttempts(state.getWalkAttempts() + 1);
            state.setLastError(error);
            state.setNextDueAt(now.plus(
                    new BackoffCalculator(settings.retry().backoff()).nextDelay(state.getWalkAttempts())));
            state.setUpdatedAt(now);
            states.save(state);
        });
    }

    /**
     * Completes the walk in progress.
     *
     * <p>The watermark moves here and only here. A full walk also advances the generation - the
     * members it saw were stamped with this value while it ran, so the counter catching up is what
     * makes them "seen in the last completed walk".
     *
     * @param stateId  the scope's state row
     * @param settings the task's settings
     * @return the scope's generation after completion
     */
    public long complete(UUID stateId, EnumerationSettings settings) {
        return transactions.execute(status -> {
            SyncScopeState state = states.getByIdOrThrow(stateId);
            Instant now = Instant.now();
            Instant highWater = state.getHighWater();
            if (highWater != null && (state.getWatermark() == null || highWater.isAfter(state.getWatermark()))) {
                state.setWatermark(highWater);
            }
            if (state.getWalkKind() == ScopeWalkKind.FULL) {
                state.setGeneration(state.getGeneration() + 1);
                state.setLastFullAt(now);
            }
            // A full walk lists everything an incremental one would have, so it resets that clock too.
            state.setLastIncrementalAt(now);
            state.setWalkKind(null);
            state.setWalkCursor(null);
            state.setWalkStartedAt(null);
            state.setHighWater(null);
            state.setWalkAttempts(0);
            state.setLastError(null);
            state.setNextDueAt(WalkPlanner.nextDue(state, settings));
            state.setUpdatedAt(now);
            states.save(state);
            return state.getGeneration();
        });
    }

    /**
     * Forgets a scope whose local row is gone, and its membership with it.
     *
     * @param taskName the task
     * @param scopeKey the encoded scope key
     * @return how many membership rows were deleted
     */
    public long drop(String taskName, String scopeKey) {
        return transactions.execute(status -> {
            long deleted = members.deleteScope(taskName, scopeKey);
            states.findScope(taskName, scopeKey).ifPresent(states::delete);
            return deleted;
        });
    }

    /**
     * Withholds a sweep for an operator.
     *
     * @param stateId    the scope's state row
     * @param candidates how many members the sweep would have reported
     */
    public void holdSweep(UUID stateId, int candidates) {
        transactions.executeWithoutResult(status -> {
            SyncScopeState state = states.getByIdOrThrow(stateId);
            Instant now = Instant.now();
            if (state.getSweepHeldAt() == null) {
                state.setSweepHeldAt(now);
            }
            state.setSweepHeldCount(candidates);
            state.setUpdatedAt(now);
            states.save(state);
        });
    }

    /**
     * Clears a withheld sweep, because it was performed, discarded or is no longer implausible.
     *
     * @param stateId the scope's state row
     */
    public void releaseSweep(UUID stateId) {
        transactions.executeWithoutResult(status -> {
            SyncScopeState state = states.getByIdOrThrow(stateId);
            if (state.getSweepHeldAt() == null) {
                return;
            }
            state.setSweepHeldAt(null);
            state.setSweepHeldCount(0);
            state.setUpdatedAt(Instant.now());
            states.save(state);
        });
    }

    /**
     * Makes a scope's next walk a full one, due now.
     *
     * <p>Membership rows are kept: a reset is "list it all again", not "forget what was there", and
     * forgetting would make the next walk report nothing as gone.
     *
     * @param stateId       the scope's state row
     * @param abandonWalk   whether to drop a walk in progress and its cursor
     * @param clearWatermark whether to forget the incremental watermark as well
     */
    public void forceFullWalk(UUID stateId, boolean abandonWalk, boolean clearWatermark) {
        transactions.executeWithoutResult(status -> {
            SyncScopeState state = states.getByIdOrThrow(stateId);
            Instant now = Instant.now();
            if (abandonWalk) {
                state.setWalkKind(null);
                state.setWalkCursor(null);
                state.setWalkStartedAt(null);
                state.setHighWater(null);
                state.setWalkAttempts(0);
                state.setLastError(null);
            }
            if (clearWatermark) {
                state.setWatermark(null);
            }
            // Null is what the planner reads as "no full walk has completed", which both forces the
            // kind and puts the scope at the front of the queue.
            state.setLastFullAt(null);
            state.setNextDueAt(now);
            state.setUpdatedAt(now);
            states.save(state);
        });
    }

    /**
     * Makes every scope of a task due for a full walk - after members were requeued, since only a full
     * walk is certain to list them again.
     *
     * @param taskName the task
     * @param stateIds the scopes
     */
    public void forceFullWalks(String taskName, Collection<UUID> stateIds) {
        stateIds.forEach(stateId -> forceFullWalk(stateId, false, false));
    }
}
