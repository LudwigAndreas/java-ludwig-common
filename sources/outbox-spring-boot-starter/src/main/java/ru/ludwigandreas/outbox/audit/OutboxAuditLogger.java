package ru.ludwigandreas.outbox.audit;

import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;

/**
 * Notified on every status transition of an {@link OutboxMessage}; {@code from} is null on creation.
 *
 * <p>Kept as an SPI after the audit consolidation, unlike the seven it replaced, because it is not only an
 * audit seam: {@link PersistingOutboxAuditLogger} writes {@code outbox_status_history}, which the dispatcher
 * and its actuator endpoint read back as operational state. The default implementation,
 * {@link AuditSinkOutboxAuditLogger}, forwards every transition to the platform's {@code AuditSink}, so a
 * service that replaces this bean is replacing the dispatcher's history hook and must forward to the trail
 * itself - the shipped wiring composes the two rather than choosing.
 */
public interface OutboxAuditLogger {

    void onTransition(OutboxMessage message, OutboxStatus from, OutboxStatus to, String detail);
}
