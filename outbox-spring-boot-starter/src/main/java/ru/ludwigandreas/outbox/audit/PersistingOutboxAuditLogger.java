package ru.ludwigandreas.outbox.audit;

import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;
import ru.ludwigandreas.outbox.entity.OutboxStatusHistory;
import ru.ludwigandreas.outbox.repository.OutboxStatusHistoryRepository;

import java.time.Instant;

/** Enabled via {@code ludwig.outbox.audit.persist-history=true}; writes every transition to {@code outbox_status_history}. */
public class PersistingOutboxAuditLogger implements OutboxAuditLogger {

    private final OutboxStatusHistoryRepository repository;
    private final OutboxAuditLogger delegate;

    public PersistingOutboxAuditLogger(OutboxStatusHistoryRepository repository, OutboxAuditLogger delegate) {
        this.repository = repository;
        this.delegate = delegate;
    }

    @Override
    public void onTransition(OutboxMessage message, OutboxStatus from, OutboxStatus to, String detail) {
        delegate.onTransition(message, from, to, detail);

        OutboxStatusHistory history = new OutboxStatusHistory();
        history.setOutboxMessageId(message.getId());
        history.setStatus(to);
        history.setAttemptNumber(message.getAttempts());
        history.setOccurredAt(Instant.now());
        history.setDetail(detail);
        repository.save(history);
    }
}
