package ru.ludwigandreas.outbox.admin.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.outbox.admin.service.OutboxAdminViews.BulkRetryResult;
import ru.ludwigandreas.outbox.admin.service.OutboxAdminViews.Count;
import ru.ludwigandreas.outbox.admin.service.OutboxAdminViews.MessageDetail;
import ru.ludwigandreas.outbox.admin.service.OutboxAdminViews.MessageFilter;
import ru.ludwigandreas.outbox.admin.service.OutboxAdminViews.MessageView;
import ru.ludwigandreas.outbox.admin.service.OutboxAdminViews.Transition;
import ru.ludwigandreas.outbox.audit.OutboxAuditLogger;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;
import ru.ludwigandreas.outbox.repository.OutboxAdminQueries.Filter;
import ru.ludwigandreas.outbox.repository.OutboxAdminQueries;
import ru.ludwigandreas.outbox.repository.OutboxStatusHistoryRepository;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminCursor;
import ru.ludwigandreas.security.admin.AdminOutcome;
import ru.ludwigandreas.security.admin.AdminPage;

/**
 * The {@code outbox} area's operations.
 *
 * <p>Every transition goes through the outbox's own {@link OutboxAuditLogger}, so an administrative retry or discard
 * appears in a message's status history exactly as a dispatcher outcome does; the administrative audit event, which
 * names the actor, is recorded beside it in the same transaction. A refusal by the message's state does not roll
 * the transaction back, so that refusal is on the record too.
 */
public class OutboxAdminService {

    /** At most how many messages one bulk retry moves. */
    public static final int BULK_RETRY_CAP = 1000;

    private static final String RESOURCE = "outbox-message";
    private static final Set<OutboxStatus> RETRYABLE = EnumSet.of(OutboxStatus.FAILED, OutboxStatus.DEAD_LETTER);
    private static final Set<OutboxStatus> DISCARDABLE =
            EnumSet.of(OutboxStatus.PENDING, OutboxStatus.FAILED, OutboxStatus.DEAD_LETTER);

    private final OutboxAdminQueries queries;
    private final OutboxStatusHistoryRepository history;
    private final OutboxAuditLogger transitions;
    private final AdminAudit audit;

    /**
     * Creates the service.
     *
     * @param queries     the administrative queries
     * @param history     the status history
     * @param transitions the outbox's transition recorder
     * @param audit       the administrative audit
     */
    public OutboxAdminService(OutboxAdminQueries queries, OutboxStatusHistoryRepository history,
                              OutboxAuditLogger transitions, AdminAudit audit) {
        this.queries = queries;
        this.history = history;
        this.transitions = transitions;
        this.audit = audit;
    }

    /**
     * One page of messages, newest first.
     *
     * @param filter what to match
     * @param cursor the previous page's cursor, or null
     * @param size   the requested page size, or null
     * @return the page
     */
    @Transactional(readOnly = true)
    public AdminPage<MessageView> messages(MessageFilter filter, String cursor, Integer size) {
        int pageSize = AdminCursor.pageSize(size);
        Instant afterCreatedAt = null;
        UUID afterId = null;
        if (cursor != null) {
            List<String> position = AdminCursor.decode(cursor, 2);
            afterCreatedAt = Instant.parse(position.get(0));
            afterId = UUID.fromString(position.get(1));
        }
        List<MessageView> rows = queries.page(toQuery(filter), afterCreatedAt, afterId, pageSize + 1).stream()
                .map(MessageView::of).toList();
        return AdminPage.of(rows, pageSize, row -> AdminCursor.encode(row.createdAt().toString(), row.id().toString()));
    }

    /**
     * One message with its history and last error.
     *
     * @param id the message id
     * @return the detail
     */
    @Transactional(readOnly = true)
    public MessageDetail message(UUID id) {
        OutboxMessage message = queries.find(id);
        if (message == null) {
            throw OutboxAdminException.notFound(id);
        }
        return new MessageDetail(MessageView.of(message), message.getLastError(),
                history.findByOutboxMessageIdOrderByOccurredAtAsc(id).stream().map(Transition::of).toList());
    }

    /**
     * How many messages there are per state and destination.
     *
     * @return the counts
     */
    @Transactional(readOnly = true)
    public List<Count> counts() {
        return queries.countsByStatusAndDestination().stream()
                .map(row -> new Count(row.get(0, OutboxStatus.class), row.get(1, String.class),
                        row.get(2, Long.class)))
                .toList();
    }

    /**
     * Moves a failed or dead-lettered message back to {@code PENDING} with its attempts reset.
     *
     * @param id the message id
     * @return the message as it now stands
     */
    @Transactional(noRollbackFor = OutboxAdminException.class)
    public MessageView retry(UUID id) {
        OutboxMessage message = lock(id);
        if (!RETRYABLE.contains(message.getStatus())) {
            audit.record(RESOURCE, id.toString(), AdminOutcome.REFUSED_BY_STATE);
            throw OutboxAdminException.notRetryable(id, message.getStatus());
        }
        moveToPending(message);
        audit.record(RESOURCE, id.toString(), AdminOutcome.SUCCEEDED);
        return MessageView.of(message);
    }

    /**
     * Retries at most {@link #BULK_RETRY_CAP} of the oldest matching messages.
     *
     * @param filter what to match; the status must be {@code FAILED} or {@code DEAD_LETTER}
     * @return how many moved, and whether more still match
     */
    @Transactional
    public BulkRetryResult retryAll(MessageFilter filter) {
        if (filter.status() == null || !RETRYABLE.contains(filter.status())) {
            throw OutboxAdminException.bulkStatusRequired();
        }
        Filter query = toQuery(filter);
        List<OutboxMessage> batch = queries.lockOldestMatching(query, BULK_RETRY_CAP);
        batch.forEach(this::moveToPending);
        boolean more = queries.anyMatching(query);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("status", filter.status().name());
        if (filter.eventType() != null) {
            facts.put("eventType", filter.eventType());
        }
        if (filter.destination() != null) {
            facts.put("destination", filter.destination());
        }
        if (filter.createdBefore() != null) {
            facts.put("createdBefore", filter.createdBefore().toString());
        }
        facts.put("moved", batch.size());
        audit.record(RESOURCE, null, AdminOutcome.SUCCEEDED, facts);
        return new BulkRetryResult(batch.size(), more);
    }

    /**
     * Moves a message that has not been published to {@code DISCARDED}, from which it is never sent.
     *
     * @param id the message id
     * @return the message as it now stands
     */
    @Transactional(noRollbackFor = OutboxAdminException.class)
    public MessageView discard(UUID id) {
        OutboxMessage message = lock(id);
        if (!DISCARDABLE.contains(message.getStatus())) {
            audit.record(RESOURCE, id.toString(), AdminOutcome.REFUSED_BY_STATE);
            throw OutboxAdminException.notDiscardable(id, message.getStatus());
        }
        OutboxStatus from = message.getStatus();
        message.setStatus(OutboxStatus.DISCARDED);
        transitions.onTransition(message, from, OutboxStatus.DISCARDED, "discarded by an administrator");
        audit.record(RESOURCE, id.toString(), AdminOutcome.SUCCEEDED);
        return MessageView.of(message);
    }

    private static Filter toQuery(MessageFilter filter) {
        return new Filter(filter.status(), filter.eventType(), filter.destination(), filter.createdBefore());
    }

    private OutboxMessage lock(UUID id) {
        OutboxMessage message = queries.lockById(id);
        if (message == null) {
            throw OutboxAdminException.notFound(id);
        }
        return message;
    }

    private void moveToPending(OutboxMessage message) {
        OutboxStatus from = message.getStatus();
        message.setStatus(OutboxStatus.PENDING);
        message.setAttempts(0);
        message.setNextAttemptAt(Instant.now());
        transitions.onTransition(message, from, OutboxStatus.PENDING, "retried by an administrator");
    }
}
