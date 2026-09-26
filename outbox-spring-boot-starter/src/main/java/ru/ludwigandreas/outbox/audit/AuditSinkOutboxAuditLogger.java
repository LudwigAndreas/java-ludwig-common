package ru.ludwigandreas.outbox.audit;

import java.time.Instant;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;

/**
 * The default {@link OutboxAuditLogger}: every transition into the platform's audit trail.
 *
 * <p>Replaces {@code Slf4jOutboxAuditLogger}, which logged one line to
 * {@code ru.ludwigandreas.outbox.audit}. A deployment grepping for that logger name will stop finding it;
 * the same information is on {@code ru.ludwigandreas.audit} with {@code category=outbox}, and this module's
 * README says so.
 */
public class AuditSinkOutboxAuditLogger implements OutboxAuditLogger {

    private final AuditSink auditSink;

    /**
     * Creates the logger.
     *
     * @param auditSink where transitions go
     */
    public AuditSinkOutboxAuditLogger(AuditSink auditSink) {
        this.auditSink = auditSink;
    }

    @Override
    public void onTransition(OutboxMessage message, OutboxStatus from, OutboxStatus to, String detail) {
        auditSink.record(new OutboxTransitionAudit(message, from, to, detail, Instant.now())
                .toAuditEvent());
    }
}
