package ru.ludwigandreas.fileaction.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.Resource;

/**
 * What happened to one submission, in this module's own vocabulary, with a conversion to the platform envelope.
 *
 * <h2>Why a typed record and not an {@code AuditEvent} directly</h2>
 *
 * <p>The convention {@code CLAUDE.md} sets out: the typed record is the authoring surface and
 * {@link #toAuditEvent()} is the only place the envelope is assembled. That keeps every call site naming its
 * fields - so a missing one is a compile error rather than a null in a map - while there remains exactly one
 * audit sink and one envelope for the whole platform.
 *
 * <p>There is deliberately no SPI here, no logger named {@code *.audit}, and no redaction mask: there is one
 * mask, on {@code Redaction.MASK}, and it is not configurable. {@code RuleGroup.AUDIT} and Checkstyle's
 * {@code SecondRedactionMask} fail the build on the first two and the third respectively.
 *
 * <h2>What is recorded and what is not</h2>
 *
 * <p>The filename, the content hash, the size, the counts and the outcome. Not any cell of the file: an audit
 * trail is read by operators and kept for longer than the file is, and a row of somebody's order data in it
 * would be a copy of personal data with a different retention policy from the file it came from. The hash is
 * what links an audit entry to the stored object without duplicating its contents - the same reasoning
 * {@code idempotency}'s {@code RequestFingerprint} gives for hashing a body rather than storing it.
 *
 * @param action       the configured action
 * @param submissionId the submission, which is also the operation id a client polls
 * @param auditAction  what happened, from {@link FileActionAuditActions}
 * @param actor        who did it
 * @param filename     the name the client sent
 * @param contentSha256 the content hash, which links to the stored object without copying its contents
 * @param sizeBytes    how large the submission was
 * @param rowsRead     how many data rows were read, or null when nothing was read yet
 * @param rowsApplied  how many rows were applied, or null
 * @param rowsRejected how many were refused, or null
 * @param scanner      which scanner answered, when one did
 * @param reasonCode   why, on a refusal
 * @param correlationId the correlation id of the request
 * @param occurredAt   when
 * @param succeeded    whether this was a success; a refusal is an audit record of a denial, not of a failure
 */
@Builder
public record FileActionAuditEvent(String action, UUID submissionId, String auditAction, Actor actor,
                                   String filename, String contentSha256, Long sizeBytes, Long rowsRead,
                                   Long rowsApplied, Long rowsRejected, String scanner, String reasonCode,
                                   String correlationId, Instant occurredAt, boolean succeeded) {

    /** The audit category every event from this module carries. */
    public static final String CATEGORY = "file-action";

    /** The resource type every event from this module names. */
    public static final String RESOURCE_TYPE = "file-action-submission";

    /** Rejects an event that cannot be attributed to an action or a submission. */
    // SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor, assembled by the generated
    // builder so that every field is named at the call site.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public FileActionAuditEvent {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("a file-action audit event needs its action");
        }
        if (auditAction == null || auditAction.isBlank()) {
            throw new IllegalArgumentException("a file-action audit event needs to say what happened");
        }
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    /**
     * The platform envelope for this event.
     *
     * <p>The one place the envelope is assembled, which is what makes "one audit mechanism" true rather than
     * merely stated.
     *
     * @return the event, ready for the sink
     */
    public AuditEvent toAuditEvent() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("action", action);
        attributes.put("filename", filename);
        attributes.put("contentSha256", contentSha256);
        attributes.put("sizeBytes", sizeBytes);
        attributes.put("rowsRead", rowsRead);
        attributes.put("rowsApplied", rowsApplied);
        attributes.put("rowsRejected", rowsRejected);
        attributes.put("scanner", scanner);
        attributes.put("reasonCode", reasonCode);
        return AuditEvent.builder()
                .category(CATEGORY)
                .action(auditAction)
                .actor(actor)
                .resource(Resource.of(RESOURCE_TYPE,
                        submissionId == null ? null : submissionId.toString()))
                .outcome(succeeded ? AuditOutcome.success()
                        : AuditOutcome.denied(reasonCode == null ? auditAction : reasonCode))
                .correlationId(correlationId)
                .occurredAt(occurredAt)
                .attributes(attributes)
                .build();
    }
}
