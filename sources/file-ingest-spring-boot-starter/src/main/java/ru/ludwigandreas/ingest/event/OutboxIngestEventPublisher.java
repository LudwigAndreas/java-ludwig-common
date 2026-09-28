package ru.ludwigandreas.ingest.event;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.ingest.api.IngestRunSummary;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.api.OutboxEventPublisher;

/**
 * Publishes {@code ingest.completed} through the transactional outbox.
 *
 * <p>Through the outbox and not directly onto a broker, for the reason the outbox exists and which
 * {@code OutboxReportEventPublisher} in the export starter gives about itself: the event and the run's
 * {@code COMPLETED} status are written in one transaction, so there is no window in which four million
 * records have landed and nobody will ever be told, and none in which somebody is told about a run
 * that rolled back. A publish on the way out of the transaction has both.
 *
 * <p>The run id is the ordering key and the idempotency key, copying the same pattern. Ordering,
 * because a later event about the same run - failed, re-run, purged - has to arrive after this one and
 * the key is what makes that true without anybody remembering to add it. Idempotency, because the
 * outbox delivers at least once and a redelivery must be recognisable as the same completion rather
 * than as a second one; a downstream service that reacted twice to a four-million-row import is a
 * worse outcome than one that missed it, because the second is noticed.
 */
@Slf4j
public class OutboxIngestEventPublisher implements IngestEventPublisher {

    private final OutboxEventPublisher outbox;

    /**
     * Creates the publisher.
     *
     * @param outbox the platform's outbox
     */
    public OutboxIngestEventPublisher(OutboxEventPublisher outbox) {
        this.outbox = outbox;
    }

    @Override
    public void completed(IngestRunSummary summary) {
        outbox.publish(OutboxEvent.builder()
                .aggregateType(IngestRunSummary.AGGREGATE_TYPE)
                .aggregateId(summary.runId().toString())
                .eventType(IngestRunSummary.EVENT_TYPE)
                .payload(summary)
                .orderingKey(summary.runId().toString())
                .idempotencyKey(summary.runId().toString())
                .headers(Map.of("task", summary.task()))
                .build());
        log.debug("Published {} for run {} ({} records applied)", IngestRunSummary.EVENT_TYPE,
                summary.runId(), summary.recordsApplied());
    }
}
