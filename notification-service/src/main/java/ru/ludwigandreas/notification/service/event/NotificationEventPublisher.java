package ru.ludwigandreas.notification.service.event;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.api.OutboxEventPublisher;

/**
 * Publishes the lifecycle events other services react to, through the transactional outbox.
 *
 * <h2>The one thing the outbox is used for here</h2>
 *
 * <p>This is the whole of this service's outbox usage, and the distinction from the delivery queue is
 * the single most important architectural decision in the module. An outbox row exists to make an
 * event's publication atomic with a local state change, and that is exactly the problem here: a
 * delivery that committed as {@code DEAD} must produce a {@code NotificationFailed} event, and an
 * event must never escape for a transition that rolled back. The outbox solves that and nothing else
 * does.
 *
 * <p>What the outbox is <em>not</em> is a work queue. It has no channel, no priority lane, no
 * schedule, no per-provider rate limit, no suppression outcome and no per-item retry semantics that
 * distinguish "the mailbox is full" from "the address does not exist". Using it for deliveries would
 * have looked like reuse and would have produced a queue that cannot express most of what this
 * service does.
 *
 * <p>{@code MANDATORY} propagation, because {@code OutboxEventPublisher} requires an ambient
 * transaction and says so: publishing outside one silently breaks the atomicity the pattern exists
 * for. Declaring it here turns that into a startup-time contract rather than a runtime surprise.
 */
@Service
@RequiredArgsConstructor
public class NotificationEventPublisher {

    private final OutboxEventPublisher outbox;
    private final NotificationProperties properties;

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishDelivered(NotificationDeliveryEntity delivery) {
        publish(delivery, NotificationEventType.DELIVERED, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishFailed(NotificationDeliveryEntity delivery, String reason) {
        publish(delivery, NotificationEventType.FAILED, reason);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishSuppressed(NotificationDeliveryEntity delivery, String reason) {
        publish(delivery, NotificationEventType.SUPPRESSED, reason);
    }

    private void publish(NotificationDeliveryEntity delivery, String eventType, String reason) {
        if (!properties.getEvents().isEnabled()) {
            return;
        }
        NotificationLifecyclePayload payload = new NotificationLifecyclePayload(
                delivery.getId(),
                delivery.getRequestId(),
                delivery.getTemplateKey(),
                delivery.getCategory(),
                delivery.getChannel().name(),
                delivery.getRecipientUserId(),
                delivery.getStatus().name(),
                reason,
                delivery.getAttempts(),
                Instant.now());

        outbox.publish(OutboxEvent.builder()
                .aggregateType("NotificationDelivery")
                .aggregateId(delivery.getId().toString())
                .eventType(eventType)
                .payload(payload)
                .route(properties.getEvents().getRoute())
                // Keeps one delivery's own events in order relative to each other - a SENT followed
                // by a DELIVERED receipt must not arrive the other way round - without serializing
                // the whole outbox behind a single key.
                .orderingKey(delivery.getId().toString())
                // A redelivered receipt or a re-run outcome re-uses the row rather than publishing
                // the same state change twice. The attempt count is part of the key so a genuine
                // second failure of the same delivery is a genuinely new event.
                .idempotencyKey(delivery.getId() + ":" + eventType + ":" + delivery.getAttempts())
                .traceId(delivery.getCorrelationId())
                .build());
    }
}
