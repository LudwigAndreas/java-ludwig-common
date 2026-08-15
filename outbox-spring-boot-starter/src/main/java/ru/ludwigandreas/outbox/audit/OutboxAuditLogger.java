package ru.ludwigandreas.outbox.audit;

import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;

/** Notified on every status transition of an {@link OutboxMessage}; {@code from} is null on creation. */
public interface OutboxAuditLogger {

    void onTransition(OutboxMessage message, OutboxStatus from, OutboxStatus to, String detail);
}
