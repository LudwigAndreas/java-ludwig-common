package ru.ludwigandreas.reconciliation.repository;

import java.time.Instant;
import java.util.Collection;

/** The set-based writes on scope membership, which a predicate executor alone cannot express. */
public interface SyncScopeMemberRepositoryCustom {

    /**
     * Stamps a page's unchanged members as seen by a walk, in one statement.
     *
     * <p>Never moves a stamp backwards, so an incremental walk that lists a member during a full
     * walk's lifetime cannot undo what the full walk recorded.
     *
     * @param taskName   the task
     * @param scopeKey   the encoded scope key
     * @param memberKeys the encoded member keys on the page
     * @param generation the generation to stamp
     * @param now        the current instant
     * @return how many rows moved
     */
    long markSeen(String taskName, String scopeKey, Collection<String> memberKeys, long generation, Instant now);

    /**
     * Forgets every member of a scope whose local row is gone.
     *
     * @param taskName the task
     * @param scopeKey the encoded scope key
     * @return how many rows were deleted
     */
    long deleteScope(String taskName, String scopeKey);

    /**
     * Returns a task's quarantined members to the queue with a fresh attempt budget.
     *
     * @param taskName the task
     * @param now      the current instant
     * @return how many members were requeued
     */
    long requeueQuarantined(String taskName, Instant now);
}
