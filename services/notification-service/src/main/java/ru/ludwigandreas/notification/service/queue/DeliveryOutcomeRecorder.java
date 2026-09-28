package ru.ludwigandreas.notification.service.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.service.metrics.NotificationMetrics;
import ru.ludwigandreas.notification.repository.DeliveryContentRepository;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.repository.entity.DeliveryContentEntity;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatus;
import ru.ludwigandreas.notification.repository.entity.FailureKind;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.service.event.NotificationEventPublisher;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.FailureClass;
import ru.ludwigandreas.notification.service.model.Priority;
import ru.ludwigandreas.notification.service.template.RenderedTemplate;

/**
 * Writes what happened to one delivery, each outcome in its own short transaction.
 *
 * <p>Per delivery rather than per batch, deliberately: one delivery's failure must not roll back
 * another's success. A batch-wide transaction would mean a single constraint violation on the
 * ninetieth row discarding the record of eighty-nine messages that have already left the building -
 * and they cannot be un-sent, so the next cycle would send them again.
 *
 * <p>A separate bean from {@code DeliveryDispatchService} for the same reason
 * {@link DeliveryClaimService} is: self-invocation bypasses the transactional proxy silently.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryOutcomeRecorder {

    private final NotificationDeliveryRepository deliveryRepository;
    private final DeliveryContentRepository contentRepository;
    private final DeliveryStatusRecorder statusRecorder;
    private final DeliveryBackoffCalculator backoffCalculator;
    private final NotificationEventPublisher eventPublisher;
    private final NotificationProperties properties;
    private final NotificationMetrics metrics;

    /**
     * Stores what was rendered, before the send is attempted.
     *
     * <p>Before rather than after, so that a message the provider accepted and a message whose
     * outcome we never learned both have their content on record. If the pod dies between the
     * provider call and the outcome, the delivery is reclaimed and retried - and an operator
     * reconstructing what happened can still see exactly what text was involved.
     */
    @Transactional
    public void recordRendered(UUID deliveryId, RenderedTemplate rendered) {
        NotificationDeliveryEntity delivery = deliveryRepository.getByIdOrThrow(deliveryId);
        delivery.setTemplateVersion(rendered.templateVersion());

        if (!properties.getTemplates().isStoreRenderedBody()) {
            return;
        }
        Instant now = Instant.now();
        // The id is set rather than built: it is declared on db-core's JpaBaseEntity, and a Lombok
        // builder only covers the fields its own class declares.
        DeliveryContentEntity content = contentRepository.findById(deliveryId)
                .orElseGet(DeliveryContentEntity::new);
        content.setId(deliveryId);
        content.setSubject(rendered.subject());
        content.setBodyHtml(rendered.htmlBody());
        content.setBodyText(rendered.textBody());
        content.setRenderedAt(now);
        // Its own purge date rather than one derived from the delivery's, so shortening the content
        // retention takes effect on rows that already exist instead of only on new ones.
        content.setPurgeAfter(now.plus(properties.getRetention().getContentTtl()));
        contentRepository.save(content);
    }

    /** The provider accepted the message. */
    @Transactional
    public void recordSent(UUID deliveryId, String providerMessageId) {
        NotificationDeliveryEntity delivery = deliveryRepository.getByIdOrThrow(deliveryId);
        Instant sentAt = Instant.now();

        delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setProviderMessageId(providerMessageId);
        delivery.setSentAt(sentAt);
        delivery.setLastError(null);
        delivery.setLastFailureKind(null);
        statusRecorder.transition(delivery, DeliveryStatus.SENT, "accepted by the provider");

        ChannelType channel = ChannelType.valueOf(delivery.getChannel().name());
        metrics.recordSendSucceeded(channel);
        metrics.recordEndToEndLatency(channel, Priority.valueOf(delivery.getPriority().name()),
                Duration.between(delivery.getCreatedAt(), sentAt));
        eventPublisher.publishDelivered(delivery);
    }

    /**
     * The provider did not accept it.
     *
     * <p>Where the retry-or-die decision is made, and the rule is stated once here rather than at
     * each channel: a terminal failure never retries whatever the budget says, and a retryable one
     * retries until the budget snapshotted on the row runs out.
     */
    @Transactional
    public void recordFailure(UUID deliveryId, String reason, FailureClass failureClass) {
        NotificationDeliveryEntity delivery = deliveryRepository.getByIdOrThrow(deliveryId);
        delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setLastError(reason);
        delivery.setLastFailureKind(FailureKind.valueOf(failureClass.name()));

        ChannelType channel = ChannelType.valueOf(delivery.getChannel().name());
        boolean exhausted = failureClass == FailureClass.TERMINAL
                || delivery.getAttempts() >= delivery.getMaxAttempts();

        if (exhausted) {
            statusRecorder.transition(delivery, DeliveryStatus.DEAD, reason);
            metrics.recordDeadLettered(channel);
            eventPublisher.publishFailed(delivery, reason);
            log.warn("Delivery {} on {} is dead after {} attempt(s): {}",
                    deliveryId, channel, delivery.getAttempts(), reason);
            return;
        }

        Duration delay = backoffCalculator.nextDelay(delivery.getAttempts());
        delivery.setNextAttemptAt(Instant.now().plus(delay));
        statusRecorder.transition(delivery, DeliveryStatus.FAILED,
                reason + " (retrying in " + delay.toSeconds() + "s)");
        metrics.recordSendFailed(channel, failureClass.name());
    }

    /**
     * The delivery was not attempted, because the destination became suppressed while it waited.
     *
     * <p>Attempts are deliberately not incremented: nothing was attempted, and counting it would eat
     * a retry that a later un-suppression might have needed.
     */
    @Transactional
    public void recordSuppressed(UUID deliveryId, String reason) {
        NotificationDeliveryEntity delivery = deliveryRepository.getByIdOrThrow(deliveryId);
        delivery.setSuppressionReason(reason);
        statusRecorder.transition(delivery, DeliveryStatus.SUPPRESSED, reason);
        metrics.recordDeliverySuppressed(ChannelType.valueOf(delivery.getChannel().name()), reason);
        eventPublisher.publishSuppressed(delivery, reason);
    }
}
