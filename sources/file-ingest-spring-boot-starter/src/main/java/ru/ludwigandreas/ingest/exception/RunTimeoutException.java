package ru.ludwigandreas.ingest.exception;

import java.time.Duration;

/**
 * The run has been going longer than its task allows, so it stops where it is.
 *
 * <h2>Why a wall-clock budget exists when the socket already has a timeout</h2>
 *
 * <p>The socket timeout is per-read: it catches a store that accepts a connection and then sends
 * nothing. It cannot catch a run that is making progress and will not finish - a partner whose nightly
 * file grew tenfold, a staging table whose indexes have degraded, a stream that is genuinely endless
 * because something upstream is generating it. Those runs read a byte often enough to keep every
 * lower-level timeout satisfied while running into the next business day.
 *
 * <p>Abandoning is cheap here for the same reason a lost lease is: the checkpoint means the next pass
 * resumes from where this one committed, so the cost of stopping is at most one batch. A run that
 * genuinely needs longer than its budget is telling its operator something, and the right response is
 * to raise {@code schedule.run-timeout} deliberately rather than to have had no budget at all.
 */
public class RunTimeoutException extends IngestException {

    private static final long serialVersionUID = 1L;

    /**
     * A run past its budget.
     *
     * @param task             the task name
     * @param elapsed          how long it had been running
     * @param budget           the configured budget
     * @param recordsCommitted how far it got, so the log says what will not need redoing
     */
    public RunTimeoutException(String task, Duration elapsed, Duration budget, long recordsCommitted) {
        super("Ingest task '" + task + "' has been running for " + elapsed + ", past its"
                + " schedule.run-timeout of " + budget + ", and stopped after " + recordsCommitted
                + " committed records. The next run resumes from the committed checkpoint; raise the"
                + " budget if the file has legitimately grown.");
    }
}
