package ru.ludwigandreas.outbox.admin.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;
import ru.ludwigandreas.outbox.entity.OutboxStatusHistory;

/**
 * What the {@code outbox} area answers with. None of these carries a payload or a header value; the listing does
 * not carry the last error either, whose text may quote the payload.
 */
public final class OutboxAdminViews {

    private OutboxAdminViews() {
    }

    /**
     * One message in a listing.
     *
     * @param id            the message id
     * @param eventType     its event type
     * @param aggregateType the aggregate it belongs to
     * @param aggregateId   the aggregate's id
     * @param transport     the transport it is routed to
     * @param destination   where it goes
     * @param status        its state
     * @param attempts      how many dispatches were attempted
     * @param maxAttempts   the budget
     * @param createdAt     when it was written
     * @param nextAttemptAt when it is next eligible
     * @param publishedAt   when it was published, if it was
     */
    public record MessageView(UUID id, String eventType, String aggregateType, String aggregateId, String transport,
                              String destination, OutboxStatus status, int attempts, int maxAttempts,
                              Instant createdAt, Instant nextAttemptAt, Instant publishedAt) {

        static MessageView of(OutboxMessage message) {
            return new MessageView(message.getId(), message.getEventType(), message.getAggregateType(),
                    message.getAggregateId(), message.getTransport(), message.getDestination(), message.getStatus(),
                    message.getAttempts(), message.getMaxAttempts(), message.getCreatedAt(),
                    message.getNextAttemptAt(), message.getPublishedAt());
        }
    }

    /**
     * One message with its history.
     *
     * @param message   the message
     * @param lastError the last dispatch error as stored, which may quote the payload - shown here only
     * @param history   its transitions, oldest first
     */
    public record MessageDetail(MessageView message, String lastError, List<Transition> history) {
    }

    /**
     * One recorded transition.
     *
     * @param status        the state entered
     * @param attemptNumber the attempt it belongs to
     * @param occurredAt    when
     * @param detail        what the recorder said about it
     */
    public record Transition(OutboxStatus status, int attemptNumber, Instant occurredAt, String detail) {

        static Transition of(OutboxStatusHistory history) {
            return new Transition(history.getStatus(), history.getAttemptNumber(), history.getOccurredAt(),
                    history.getDetail());
        }
    }

    /**
     * How many messages are in one state for one destination.
     *
     * @param status      the state
     * @param destination the destination
     * @param count       how many
     */
    public record Count(OutboxStatus status, String destination, long count) {
    }

    /**
     * What a listing or a bulk retry matches; every member is optional.
     *
     * @param status        the state
     * @param eventType     the event type
     * @param destination   the destination
     * @param createdBefore only messages created before this instant
     */
    public record MessageFilter(OutboxStatus status, String eventType, String destination, Instant createdBefore) {
    }

    /**
     * What a bulk retry did.
     *
     * @param moved how many messages it moved back to {@code PENDING}
     * @param more  whether further messages still match
     */
    public record BulkRetryResult(int moved, boolean more) {
    }
}
