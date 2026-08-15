package ru.ludwigandreas.outbox.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;

public class Slf4jOutboxAuditLogger implements OutboxAuditLogger {

    private static final Logger log = LoggerFactory.getLogger("ru.ludwigandreas.outbox.audit");

    @Override
    public void onTransition(OutboxMessage message, OutboxStatus from, OutboxStatus to, String detail) {
        log.info("outbox message id={} eventType={} aggregateType={} aggregateId={} attempts={} {} -> {} ({})",
                message.getId(), message.getEventType(), message.getAggregateType(), message.getAggregateId(),
                message.getAttempts(), from, to, detail);
    }
}
