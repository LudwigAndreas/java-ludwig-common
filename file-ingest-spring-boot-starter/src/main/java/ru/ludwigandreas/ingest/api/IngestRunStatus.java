package ru.ludwigandreas.ingest.api;

/**
 * Where a run is.
 *
 * <p>Deliberately small. Every state here is one an operator has to be able to act on differently,
 * and a state nobody would act on differently is a field rather than a status - which is why "reading"
 * and "merging" are not states: both are {@code RUNNING} with a checkpoint that is or is not moving,
 * and the checkpoint answers the question better than a status would.
 */
public enum IngestRunStatus {

    /**
     * Claimed and in progress, with a checkpoint that advances.
     *
     * <p>A run left in this state by a crashed instance is not stuck: the lock's lease expires, the
     * next scheduled pass finds the run, and it resumes from the committed checkpoint. There is no
     * reaper and no timeout sweep, because the lease already is one.
     */
    RUNNING,

    /**
     * Every record accounted for, the merge applied, the balance check passed.
     *
     * <p>The source of truth, and it is written <em>before</em> the receipt and the archive. A crash
     * after this point re-runs, finds the run already complete through the identity constraint, and
     * redoes only the idempotent bucket operations. The reverse order would leave a bucket saying
     * "done" and a database saying "never ran".
     */
    COMPLETED,

    /**
     * The run stopped and will not be resumed as it stands.
     *
     * <p>Covers an unreadable object, a quarantine rate past the task's threshold, and - the one worth
     * naming - a balance check that did not balance. A failed run keeps its checkpoint and its counts,
     * because the numbers are the evidence.
     */
    FAILED
}
