package ru.ludwigandreas.reconciliation.repository;

import com.querydsl.core.types.Predicate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.ludwigandreas.reconciliation.entity.SyncRun;

/** The run-history queries that are not derived: the keyset page, the bounded purge and the abandoned-run sweep. */
public interface SyncRunRepositoryCustom {

    /**
     * A task's runs, newest first, after a keyset position.
     *
     * @param taskName         the task
     * @param filter           further narrowing (tier, status), or null for none
     * @param afterSubmittedAt the last returned run's submission time, or null for the first page
     * @param afterId          the last returned run's id, or null
     * @param limit            at most this many
     * @return the runs
     */
    List<SyncRun> findPage(String taskName, Predicate filter, Instant afterSubmittedAt, UUID afterId, int limit);

    /**
     * Deletes up to {@code limit} runs that finished before {@code cutoff}.
     *
     * @param cutoff the retention boundary
     * @param limit  at most this many
     * @return how many were deleted
     */
    long purgeFinishedBefore(Instant cutoff, int limit);

    /**
     * Ends a task's unfinished runs that were submitted before {@code cutoff} as failed, with {@code failureCode}.
     *
     * <p>A run outlives its lease only when its replica died: the lease is the run timeout. Without this the row
     * would read as running for ever.
     *
     * @param taskName    the task
     * @param cutoff      runs submitted before this are past any lease they could still hold
     * @param failureCode what to record
     * @param now         when they are ended
     * @return how many were ended
     */
    long abandonUnfinishedBefore(String taskName, Instant cutoff, String failureCode, Instant now);
}
