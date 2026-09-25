package ru.ludwigandreas.ingest.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One thing that happened to a run, in the form an audit sink receives it.
 *
 * <p>A flat record with a details map rather than a type per event, for the reason the reconciliation
 * module's equivalent gives: a sink is almost always writing these somewhere structured and generic -
 * a log pipeline, a table, an external audit service - and a hierarchy would make every sink switch
 * over a set of types that grows without its participation.
 *
 * @param at      when it happened
 * @param runId   the run, or {@code null} for an event about a task rather than a run - a file that
 *                did not arrive, for instance
 * @param task    the task name
 * @param action  what happened, lower-case and stable; part of the contract a sink may branch on
 * @param details anything else worth recording, such as the counts at the moment of the event
 */
public record IngestAuditEvent(Instant at, UUID runId, String task, String action,
                               Map<String, Object> details) {

    /** A run claimed an object. */
    public static final String CLAIMED = "claimed";

    /** A run resumed from a committed checkpoint. */
    public static final String RESUMED = "resumed";

    /** A batch was committed together with its checkpoint. */
    public static final String BATCH_COMMITTED = "batch-committed";

    /** A record was quarantined. */
    public static final String QUARANTINED = "quarantined";

    /** Staging was merged into the target. */
    public static final String MERGED = "merged";

    /** A run reached COMPLETED. */
    public static final String COMPLETED = "completed";

    /** A run failed, including because it could not balance. */
    public static final String FAILED = "failed";

    /** An object was offered that a previous run had already ingested. */
    public static final String SKIPPED_ALREADY_PROCESSED = "skipped-already-processed";

    /** A task's expected-by time passed with no file. */
    public static final String MISSING = "missing";

    /**
     * Normalises the details map.
     */
    public IngestAuditEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
