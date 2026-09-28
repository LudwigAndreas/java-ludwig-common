package ru.ludwigandreas.outbox.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.Resource;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;

/**
 * One status transition of an outbox message, in the form the platform's audit trail receives it.
 *
 * <p>This module had no event type at all - {@code OutboxAuditLogger} took four loose arguments - which is
 * why this record is new rather than kept. The SPI stays, because the dispatcher's own callers depend on it
 * and on {@code PersistingOutboxAuditLogger} writing {@code outbox_status_history}; what is new is one place
 * that turns a transition into an {@link AuditEvent}, so the same transition reaches both the dispatcher's
 * operational history and the platform trail without either being derived from the other's shape.
 *
 * @param message the message that moved
 * @param from    the status it moved out of, or {@code null} on creation
 * @param to      the status it moved into
 * @param detail  free text: why it failed, what dispatched it
 * @param at      when
 */
public record OutboxTransitionAudit(OutboxMessage message, OutboxStatus from, OutboxStatus to,
                                   String detail, Instant at) {

    /** The resource type every outbox event is about. */
    public static final String RESOURCE_TYPE = "outbox-message";

    /**
     * This transition as a platform audit event.
     *
     * <p>{@code DEAD_LETTER} and {@code FAILED} are {@link AuditOutcome.Status#FAILURE}; everything else is a
     * success, because a message moving to {@code PROCESSING} or {@code PUBLISHED} is the pattern working.
     * A dead-lettered message in particular has to be adverse in the trail: it is the one outbox state that
     * means a downstream system will never be told something happened, and it is the reason anybody reads
     * this part of the trail at all.
     *
     * <p>The payload is never included. An outbox message's payload is the business event in full, and the
     * audit trail is retained longer than the outbox row and read by more people - the same rule
     * {@code OutboundCallAudit} states for response bodies.
     *
     * @return the event
     */
    public AuditEvent toAuditEvent() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("eventType", message.getEventType());
        attributes.put("aggregateType", message.getAggregateType());
        attributes.put("aggregateId", message.getAggregateId());
        attributes.put("attempts", message.getAttempts());
        attributes.put("fromStatus", from == null ? null : from.name());
        attributes.put("toStatus", to == null ? null : to.name());
        return AuditEvent.builder()
                .category(AuditCategories.OUTBOX)
                .action("outbox." + (to == null ? "unknown" : to.name().toLowerCase(java.util.Locale.ROOT)))
                .occurredAt(at)
                // The dispatcher, not a person: an outbox transition happens on a scheduler thread, and the
                // principal who published the message is recorded on the event the message carries.
                .actor(Actor.system())
                .resource(new Resource(RESOURCE_TYPE, String.valueOf(message.getId()),
                        message.getEventType()))
                .outcome(adverse() ? AuditOutcome.failure(detail) : AuditOutcome.success())
                .attributes(attributes)
                .build();
    }

    private boolean adverse() {
        return to == OutboxStatus.DEAD_LETTER || to == OutboxStatus.FAILED;
    }
}
