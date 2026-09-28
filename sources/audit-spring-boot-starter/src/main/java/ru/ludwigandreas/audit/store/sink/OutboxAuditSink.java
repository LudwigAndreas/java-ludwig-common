package ru.ludwigandreas.audit.store.sink;

import java.util.LinkedHashMap;
import java.util.Map;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.api.OutboxEventPublisher;

/**
 * Ships the trail off-platform through the transactional outbox.
 *
 * <p>This is the answer to "a single place an auditor can be pointed at" when that place is a SIEM
 * rather than this service's database. Through the outbox and not straight onto a broker, for the reason
 * the outbox exists and {@code OutboxReportEventPublisher} already argued about {@code report.ready}: the
 * event and the change it describes are written in one transaction, so there is no window in which
 * something happened and the SIEM will never be told, and none in which the SIEM is told about a change
 * that rolled back. A publish on the way out of the transaction has both.
 *
 * <p>{@link AuditEvent#id()} is the ordering key and the idempotency key. As the idempotency key it makes
 * a redelivery recognisable as the same event rather than as a second thing that happened - which is
 * the difference between an audit trail and a count of how many times the broker retried. As the ordering
 * key it is deliberately <em>per event</em> rather than per actor or per resource: audit events are
 * independent facts, and keying them by actor would serialise a busy administrator's whole trail behind
 * one partition for no ordering anybody needs.
 *
 * <h2>Requires a transaction</h2>
 *
 * <p>{@code OutboxEventPublisher} is {@code Propagation.MANDATORY}, so this sink can only be used on a
 * path that already has a transaction. That rules it out for the observational categories on their own -
 * an authorization denial has none - which is why the shipped wiring composes it behind
 * {@code CompositeAuditSink} alongside the SLF4J and JPA sinks rather than as the only sink, and why
 * this module's properties gate it per category. Enabling it for a category whose events are emitted
 * outside a transaction turns every one of those events into an
 * {@code IllegalTransactionStateException}; the README says so, and
 * {@code AuditProperties.Outbox#categories} is how a deployment names the ones that qualify.
 */
public class OutboxAuditSink implements AuditSink {

    /** The aggregate type outbox rows carry, so a consumer can subscribe to the trail alone. */
    public static final String AGGREGATE_TYPE = "audit-event";

    /** The event type outbox rows carry. */
    public static final String EVENT_TYPE = "audit.event";

    private final OutboxEventPublisher outbox;

    /**
     * Creates the sink.
     *
     * @param outbox the transactional outbox
     */
    public OutboxAuditSink(OutboxEventPublisher outbox) {
        this.outbox = outbox;
    }

    @Override
    public void record(AuditEvent event) {
        outbox.publish(OutboxEvent.builder()
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(event.id().toString())
                .eventType(EVENT_TYPE)
                .payload(event)
                .orderingKey(event.id().toString())
                .idempotencyKey(event.id().toString())
                .headers(headersFor(event))
                .build());
    }

    /**
     * Category, action and outcome as message headers.
     *
     * <p>So that a SIEM's routing and a broker-side filter can select on them without deserialising the
     * payload - which is what makes "send denials to the security topic and everything else to the
     * archive" a subscription rather than a consumer that reads everything.
     */
    private Map<String, String> headersFor(AuditEvent event) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("category", event.category());
        headers.put("action", event.action());
        headers.put("outcome", event.outcome().status().name());
        return headers;
    }
}
