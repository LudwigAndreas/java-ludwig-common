package ru.ludwigandreas.ingest.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.Resource;

/**
 * One thing that happened to a run, in the form an audit sink receives it.
 *
 * <p>A flat record with a details map rather than a type per event, for the reason the reconciliation
 * module's equivalent gives: a sink is almost always writing these somewhere structured and generic -
 * a log pipeline, a table, an external audit service - and a hierarchy would make every sink switch
 * over a set of types that grows without its participation. The audit consolidation vindicated that
 * shape rather than replacing it: this record is still what the engine authors, and
 * {@link #toAuditEvent()} flattens it into the platform envelope.
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

    /** The resource type an event about a run is about. */
    public static final String RESOURCE_TYPE = "ingest-task";

    /**
     * This event as a platform audit event.
     *
     * <p>The actor is always {@link Actor#system()}: an ingest run is triggered by a schedule and a file
     * arriving, and there is no principal behind it. Recording that explicitly rather than leaving the actor
     * null is what keeps "nobody did this, it was a job" distinguishable from "we failed to resolve who did
     * this", which are different answers to the same audit question.
     *
     * <p>{@link #FAILED} and {@link #QUARANTINED} become adverse outcomes rather than successes with a
     * label. A quarantined record is a {@link AuditOutcome.Status#PARTIAL} - the pass did some of what was
     * asked - and a failed run is a {@link AuditOutcome.Status#FAILURE}.
     *
     * @return the event
     */
    public AuditEvent toAuditEvent() {
        return AuditEvent.builder()
                .category(AuditCategories.INGEST)
                .action("ingest." + action)
                .occurredAt(at)
                .actor(Actor.system())
                .resource(new Resource(RESOURCE_TYPE, task, null))
                .outcome(outcomeOf())
                .attributes(attributesWithRunId())
                .build();
    }

    private Map<String, Object> attributesWithRunId() {
        if (runId == null) {
            return details;
        }
        Map<String, Object> attributes = new LinkedHashMap<>(details);
        // The run id is an attribute rather than the resource id: the resource an ingest event is about is
        // the task, which outlives any one run, and an event about a file that never arrived has no run.
        attributes.put("runId", runId.toString());
        return attributes;
    }

    private AuditOutcome outcomeOf() {
        if (FAILED.equals(action)) {
            return AuditOutcome.failure(String.valueOf(details.get("reason")));
        }
        if (MISSING.equals(action)) {
            return AuditOutcome.failure("no file arrived by the expected time");
        }
        if (QUARANTINED.equals(action)) {
            return AuditOutcome.partial("record quarantined");
        }
        return AuditOutcome.success();
    }
}
