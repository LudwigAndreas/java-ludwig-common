package ru.ludwigandreas.export.event;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.api.OutboxEventPublisher;

/**
 * Publishes {@code report.ready} through the transactional outbox.
 *
 * <p>Through the outbox and not directly onto a broker, for the reason the outbox exists: the event
 * and the run's SUCCEEDED status are written in one transaction, so there is no window in which the
 * report is finished and nobody will ever be told, and none in which somebody is told about a run
 * that rolled back. A publish on the way out of the transaction has both.
 *
 * <p>The run id is the ordering key as well as the aggregate id. A run publishes at most one ready
 * event today, so the ordering is trivial - but a later event about the same run (expired, purged,
 * re-run) has to arrive after this one, and the key is what makes that true without anybody
 * remembering to add it.
 *
 * <p>The idempotency key is the run id too, so a redelivery is recognisable as the same event rather
 * than as a second report.
 */
@Slf4j
public class OutboxReportEventPublisher implements ReportEventPublisher {

    private final OutboxEventPublisher outbox;

    public OutboxReportEventPublisher(OutboxEventPublisher outbox) {
        this.outbox = outbox;
    }

    @Override
    public void reportReady(ReportReadyEvent event) {
        outbox.publish(OutboxEvent.builder()
                .aggregateType(ReportReadyEvent.AGGREGATE_TYPE)
                .aggregateId(event.runId().toString())
                .eventType(ReportReadyEvent.EVENT_TYPE)
                .payload(event)
                .orderingKey(event.runId().toString())
                .idempotencyKey(event.runId().toString())
                .headers(Map.of("definition", event.definitionKey()))
                .build());
        log.debug("Published {} for run {} with {} output(s)", ReportReadyEvent.EVENT_TYPE,
                event.runId(), event.outputs().size());
    }

    /** The recipients a subscription's event carries, for a caller assembling one. */
    static List<String> recipientsOf(List<String> configured) {
        return configured == null ? List.of() : List.copyOf(configured);
    }
}
