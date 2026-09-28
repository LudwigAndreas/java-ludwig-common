package ru.ludwigandreas.messaging.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.Resource;
import ru.ludwigandreas.messaging.api.InboundEnvelope;

/**
 * One record this platform accepted and will not process, in the form the audit trail receives it.
 *
 * <p>A dead-lettered record is the consumer-side twin of {@code OutboxTransitionAudit}'s
 * {@code DEAD_LETTER} transition, and it belongs in the trail for the same reason that one does: it is
 * the state that means something a system was told to do will not happen. The outbox's half has been
 * audited since that module was written. The consumer's half was audited nowhere, because two of the
 * three consumers had no dead-letter topic and the third logged its sends and nothing more.
 *
 * <p>A typed record with a {@code toAuditEvent()}, which is the shape {@code audit-core} documents and
 * an ArchUnit rule enforces: the record is the authoring surface, the envelope is the transport.
 *
 * <h2>What is deliberately not in it</h2>
 *
 * <p><b>The payload.</b> This platform's topics carry a person's settings, consent records and directory
 * data, and the audit trail is retained longer than the topic and read by more people. The same rule
 * {@code OutboxTransitionAudit} states for an outbox payload and {@code OutboundCallAudit} states for a
 * response body. The record's coordinates are what locates it: the record is still on the source topic
 * and on the dead-letter topic, both of which an operator can read.
 *
 * <p><b>The exception's message.</b> Only its type. A deserialization failure's message quotes the
 * fragment it choked on, which is payload text by another route - the same reasoning
 * {@code SettingsEventListener} already applied to its own log line.
 *
 * @param envelope        what was read off the record, payload included but never published
 * @param deadLetterTopic where the record was sent
 * @param exceptionType   the simple name of the failure's type
 * @param attempts        how many attempts were spent before giving up, or {@code 1} when the failure
 *                        was non-retryable and the record went straight out
 * @param at              when
 */
public record DeadLetterAudit(InboundEnvelope<?> envelope, String deadLetterTopic, String exceptionType,
                              int attempts, Instant at) {

    /** The resource type every event here is about. */
    public static final String RESOURCE_TYPE = "kafka-record";

    /** The action for a record that reached the dead-letter topic. */
    public static final String ACTION_DEAD_LETTERED = "messaging.dead-lettered";

    /** The action for a record whose recovery itself failed, so it went nowhere. */
    public static final String ACTION_DROPPED = "messaging.dropped";

    /**
     * This record as a platform audit event.
     *
     * <p>Always {@link AuditOutcome.Status#FAILURE}. There is no success case here - the type exists only
     * for records that were not processed - and recording it as anything else would make a dashboard
     * counting adverse audit outcomes miss the one consumer-side event that always deserves attention.
     *
     * @param recovered whether the record actually reached the dead-letter topic; {@code false} means the
     *                  recoverer failed and the record is gone, which is a materially worse event and gets
     *                  its own action so an alert can distinguish them
     * @return the event
     */
    public AuditEvent toAuditEvent(boolean recovered) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("topic", envelope.topic());
        attributes.put("partition", envelope.partition());
        attributes.put("offset", envelope.offset());
        attributes.put("eventType", envelope.eventType());
        attributes.put("eventVersion", envelope.eventVersion());
        attributes.put("aggregateType", envelope.aggregateType());
        attributes.put("aggregateId", envelope.aggregateId());
        attributes.put("deadLetterTopic", recovered ? deadLetterTopic : null);
        attributes.put("exception", exceptionType);
        attributes.put("attempts", attempts);
        return AuditEvent.builder()
                .category(AuditCategories.MESSAGING)
                .action(recovered ? ACTION_DEAD_LETTERED : ACTION_DROPPED)
                .occurredAt(at)
                // The container, not a person: this happens on a listener thread, and whoever caused the
                // event to exist is recorded on the producer side. The correlation id is what joins the
                // two, which is why it is on the envelope rather than only in a log line.
                .actor(Actor.system())
                .resource(new Resource(RESOURCE_TYPE, envelope.coordinates(), envelope.eventType()))
                .outcome(AuditOutcome.failure(reason(recovered)))
                .correlationId(envelope.correlationId())
                .traceId(envelope.traceId())
                .attributes(attributes)
                .build();
    }

    private String reason(boolean recovered) {
        return recovered
                ? "dead-lettered to " + deadLetterTopic + " after " + attempts + " attempt(s): " + exceptionType
                : "recovery to " + deadLetterTopic + " failed after " + attempts + " attempt(s): "
                        + exceptionType;
    }
}
