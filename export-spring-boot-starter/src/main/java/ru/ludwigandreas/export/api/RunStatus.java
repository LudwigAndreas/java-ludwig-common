package ru.ludwigandreas.export.api;

/**
 * Where a report run is in its life.
 *
 * <pre>
 * PENDING -&gt; RUNNING -&gt; SUCCEEDED
 *                    -&gt; FAILED     (retryable: back to PENDING until the attempt budget is spent)
 *                    -&gt; CANCELLED
 * SUCCEEDED -&gt; EXPIRED            (retention removed the file; the record stays)
 * </pre>
 *
 * <p>There is no {@code PARTIAL}, and that is a decision. A retry restarts a run from scratch rather
 * than resuming a half-written workbook, so there is never a state in which some of a file exists
 * and is meaningful. Resuming would mean persisting the source's keyset cursor, the enrichment
 * cache and the writer's internal position, and then being certain that the rows behind the cursor
 * had not changed - which is only true of a snapshot nothing in this design takes. Restarting is
 * safe because a run is idempotent on the hash of its request, and cheap enough at the design point
 * to be the right trade.
 */
public enum RunStatus {

    /** Accepted and waiting to be claimed by a poller. */
    PENDING,

    /** Claimed, leased and executing. A lease that stops being renewed returns the run to PENDING. */
    RUNNING,

    /** Finished; every output is stored and downloadable. */
    SUCCEEDED,

    /** Finished without a file. Every partial output has been deleted. */
    FAILED,

    /** Stopped on request. Takes effect within one window; the partial output is deleted. */
    CANCELLED,

    /** Succeeded once, and its outputs have since been removed by the retention purge. */
    EXPIRED
}
