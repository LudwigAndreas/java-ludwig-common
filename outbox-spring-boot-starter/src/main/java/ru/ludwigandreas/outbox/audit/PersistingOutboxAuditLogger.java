package ru.ludwigandreas.outbox.audit;

import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;
import ru.ludwigandreas.outbox.entity.OutboxStatusHistory;
import ru.ludwigandreas.outbox.repository.OutboxStatusHistoryRepository;

import java.time.Instant;

/**
 * Enabled via {@code ludwig.outbox.audit.persist-history=true}; writes every transition to {@code
 * outbox_status_history} and forwards it to {@code delegate}.
 *
 * <p>The table it writes survived the audit consolidation on purpose, and it is the only one of the three
 * persistent audit stores that did. {@code user_setting_audit} and {@code sync_audit_record} were audit
 * trails and nothing else, so they migrated into {@code audit_event}; this one is <em>operational state the
 * dispatcher itself reads</em> - attempt counts, last failure, when a message was last tried - and folding
 * it into a generic table would couple dispatch to audit retention, so a deployment shortening its audit
 * window would shorten the dispatcher's memory with it.
 *
 * <p>Written down here rather than only in a README because without the reason somebody will eventually
 * "finish the job".
 */
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
