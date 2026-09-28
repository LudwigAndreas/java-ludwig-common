package ru.ludwigandreas.outbox.api;

import ru.ludwigandreas.outbox.entity.OutboxMessage;

import java.util.List;
import java.util.Optional;

/**
 * Entry point for the transactional outbox pattern: call from inside the same {@code @Transactional}
 * business method that makes the change an event describes, so the outbox row commits atomically with
 * it. Implementations require an ambient transaction ({@code Propagation.MANDATORY}) - calling this
 * outside one is a bug (it would silently break the atomicity guarantee the whole pattern exists for)
 * and throws {@link org.springframework.transaction.IllegalTransactionStateException}.
 * <p>
 * <b>Idempotency caveat:</b> when {@link OutboxEvent#getIdempotencyKey()} is set, a sequential republish
 * of the same key returns the already-persisted row. Under a genuine concurrent double-publish of the
 * same key, the second call's flush hits the unique constraint and throws
 * {@link org.springframework.dao.DataIntegrityViolationException} - which aborts the caller's ambient
 * transaction (standard Postgres/JDBC behavior for a constraint violation inside a transaction). Callers
 * publishing on a path where that's expected (e.g. an at-least-once message handler) should treat that
 * exception as "duplicate, already handled" in their own transaction-boundary error handling.
 */
public interface OutboxEventPublisher {

    /**
     * @return the persisted row, or empty if a registered {@link ru.ludwigandreas.outbox.publisher.filter.OutboxPublishFilter} rejected the event
     */
    Optional<OutboxMessage> publish(OutboxEvent event);

    /**
     * Inserts all events in the caller's ambient transaction. Filtered-out events are simply omitted
     * from the result, in event order.
     */
    List<OutboxMessage> publishAll(List<OutboxEvent> events);
}
