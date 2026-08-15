package ru.ludwigandreas.outbox.dispatch;

import ru.ludwigandreas.outbox.entity.OutboxMessage;

/**
 * Sends one claimed message over a transport. Register a bean implementing this interface (any
 * {@link #transport()} name) to add a custom transport alongside the built-in Kafka/REST ones -
 * {@link OutboxDispatcherRegistry} picks it up automatically.
 */
public interface OutboxDispatcher {

    /** Matched case-insensitively against {@code OutboxMessage.transport} (and {@code ludwig.outbox.routes.*.transport}). */
    String transport();

    DispatchResult dispatch(OutboxMessage message);
}
