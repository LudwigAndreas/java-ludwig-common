package ru.ludwigandreas.ingest.exception;

/**
 * The run's lock lease could not be renewed, so the run stops immediately, mid-file.
 *
 * <h2>Why stopping is cheap and continuing is not</h2>
 *
 * <p>A lease that cannot be renewed means this instance may no longer be the only one running this
 * task - the lease may already have expired and been taken by a second replica, which would then be
 * reading the same object into the same staging table. Continuing risks every record being written
 * twice; stopping costs the work since the last checkpoint, which is at most one batch, because the
 * next run resumes from exactly where this one committed.
 *
 * <p>That asymmetry is the whole argument, and it is why the renewal happens inside the batch loop
 * rather than once at the top. A forty-minute ingest under a five-minute lease renewed only at the
 * start has lost the lock by minute six and will not notice.
 */
public class LeaseLostException extends IngestException {

    private static final long serialVersionUID = 1L;

    /**
     * A run whose lease lapsed.
     *
     * @param task             the task name
     * @param recordsCommitted how far it got, so the log says what will not need redoing
     */
    public LeaseLostException(String task, long recordsCommitted) {
        super("Ingest task '" + task + "' lost its run lock after " + recordsCommitted
                + " committed records and stopped mid-file. The next run resumes from the committed"
                + " checkpoint; continuing without the lock would risk a second replica importing the"
                + " same object concurrently.");
    }
}
