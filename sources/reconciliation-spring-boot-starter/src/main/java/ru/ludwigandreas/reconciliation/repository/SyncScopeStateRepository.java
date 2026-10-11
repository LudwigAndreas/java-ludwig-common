package ru.ludwigandreas.reconciliation.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import org.springframework.data.querydsl.QPageRequest;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.reconciliation.entity.QSyncScopeState;
import ru.ludwigandreas.reconciliation.entity.SyncScopeState;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-task, per-scope walk state.
 *
 * <p>Every query is a QueryDSL predicate against the generated Q-type, so a renamed column fails the
 * compile rather than a walk.
 */
public interface SyncScopeStateRepository extends BaseRepository<SyncScopeState, UUID> {

    /**
     * The state row for a task and scope.
     *
     * @param taskName the task
     * @param scopeKey the encoded scope key
     * @return the row, or empty before the scope is discovered
     */
    default Optional<SyncScopeState> findScope(String taskName, String scopeKey) {
        QSyncScopeState state = QSyncScopeState.syncScopeState;
        return findOne(state.taskName.eq(taskName).and(state.scopeKey.eq(scopeKey)));
    }

    /**
     * Which of a page of scope keys are already registered.
     *
     * @param taskName  the task
     * @param scopeKeys encoded scope keys
     * @return the rows that exist
     */
    default List<SyncScopeState> findScopes(String taskName, Collection<String> scopeKeys) {
        QSyncScopeState state = QSyncScopeState.syncScopeState;
        return toList(findAll(state.taskName.eq(taskName).and(state.scopeKey.in(scopeKeys))));
    }

    /**
     * The scopes a walk pass should take, most deserving first.
     *
     * <p>A scope that has never completed a full walk sorts ahead of one that is merely overdue: the
     * first has no data at all, the second has data that is a little old.
     *
     * @param taskName the task
     * @param now      the current instant
     * @param limit    the most scopes one run may take
     * @return the due scopes
     */
    default List<SyncScopeState> findDue(String taskName, Instant now, int limit) {
        QSyncScopeState state = QSyncScopeState.syncScopeState;
        BooleanExpression due = state.taskName.eq(taskName).and(state.nextDueAt.loe(now));
        // Two queries rather than one ordered NULLS FIRST: Spring Data's QSort does not carry a
        // null-handling hint through to the query, and Postgres sorts nulls last when ascending -
        // which would put exactly the scopes that have never been walked at the back of the queue.
        List<SyncScopeState> never = findAll(due.and(state.lastFullAt.isNull()),
                QPageRequest.of(0, limit, state.nextDueAt.asc(), state.scopeKey.asc())).getContent();
        if (never.size() >= limit) {
            return never;
        }
        List<SyncScopeState> selected = new java.util.ArrayList<>(never);
        selected.addAll(findAll(due.and(state.lastFullAt.isNotNull()),
                QPageRequest.of(0, limit - never.size(), state.nextDueAt.asc(), state.scopeKey.asc()))
                .getContent());
        return selected;
    }

    /**
     * When the scope that was listed longest ago was last listed.
     *
     * @param taskName the task
     * @return the earliest completed listing among scopes that have one, or empty
     */
    default Optional<Instant> findOldestListing(String taskName) {
        QSyncScopeState state = QSyncScopeState.syncScopeState;
        return findAll(state.taskName.eq(taskName).and(state.lastIncrementalAt.isNotNull()),
                QPageRequest.of(0, 1, state.lastIncrementalAt.asc()))
                .getContent().stream().findFirst().map(SyncScopeState::getLastIncrementalAt);
    }

    /**
     * When the scope that has waited longest for its first listing was registered.
     *
     * @param taskName the task
     * @return the earliest registration among scopes never listed, or empty
     */
    default Optional<Instant> findOldestNeverListed(String taskName) {
        QSyncScopeState state = QSyncScopeState.syncScopeState;
        return findAll(state.taskName.eq(taskName).and(state.lastIncrementalAt.isNull()),
                QPageRequest.of(0, 1, state.registeredAt.asc()))
                .getContent().stream().findFirst().map(SyncScopeState::getRegisteredAt);
    }

    /**
     * How many of a task's scopes have a sweep withheld for an operator.
     *
     * @param taskName the task
     * @return the count
     */
    default long countHeldSweeps(String taskName) {
        QSyncScopeState state = QSyncScopeState.syncScopeState;
        return count(state.taskName.eq(taskName).and(state.sweepHeldAt.isNotNull()));
    }

    /**
     * How many scopes a task has.
     *
     * @param taskName the task
     * @return the count
     */
    default long countScopes(String taskName) {
        return count(QSyncScopeState.syncScopeState.taskName.eq(taskName));
    }

    /**
     * A page of a task's scopes for the actuator's listing, least fresh first.
     *
     * @param taskName the task
     * @param limit    how many to return
     * @return the scopes
     */
    default List<SyncScopeState> sample(String taskName, int limit) {
        QSyncScopeState state = QSyncScopeState.syncScopeState;
        return findAll(state.taskName.eq(taskName),
                QPageRequest.of(0, limit, state.nextDueAt.asc(), state.scopeKey.asc())).getContent();
    }

    private static <T> List<T> toList(Iterable<T> rows) {
        List<T> list = new java.util.ArrayList<>();
        rows.forEach(list::add);
        return list;
    }
}
