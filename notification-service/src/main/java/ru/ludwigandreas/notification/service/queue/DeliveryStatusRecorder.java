package ru.ludwigandreas.notification.service.queue;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.repository.DeliveryStatusHistoryRepository;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatus;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatusHistoryEntity;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;

/**
 * Moves a delivery from one state to another and records that it happened.
 *
 * <p>One place, so it cannot be done two ways. A transition written directly on the entity at one
 * call site and through a helper at another produces a history trail with holes in it, and a trail
 * with holes is worse than none - it is read as complete.
 *
 * <p>{@code MANDATORY} propagation is the load-bearing part of the contract: the status change and
 * its history row must commit together with whatever else the caller is doing, and a transition that
 * quietly ran in its own implicit transaction would leave a trail entry for a change that then
 * rolled back. Declaring the requirement rather than assuming it means the mistake is a startup-time
 * exception instead of a subtly wrong audit record.
 */
@Service
@RequiredArgsConstructor
public class DeliveryStatusRecorder {

    private final DeliveryStatusHistoryRepository historyRepository;

    /**
     * Applies a transition and appends it to the trail.
     *
     * @param detail one short operator-facing line. Never the rendered body and never the
     *               recipient's address: the trail is kept longer than the delivery it describes, so
     *               anything personal written here outlives the retention promise made about it
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void transition(NotificationDeliveryEntity delivery, DeliveryStatus to, String detail) {
        DeliveryStatus from = delivery.getStatus();
        delivery.setStatus(to);
        if (isSettled(to)) {
            // Stamped here rather than at each call site, because it is what the retention purge
            // selects on: a terminal delivery with no settledAt would never be cleaned up, and the
            // omission would only surface as a table that grows forever.
            delivery.setSettledAt(Instant.now());
        }
        historyRepository.save(DeliveryStatusHistoryEntity.builder()
                .deliveryId(delivery.getId())
                .fromStatus(from)
                .toStatus(to)
                .attemptNumber(delivery.getAttempts())
                .occurredAt(Instant.now())
                .detail(detail)
                .build());
    }

    /** Records the initial {@code ACCEPTED} entry for a delivery that has just been created. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCreation(NotificationDeliveryEntity delivery) {
        historyRepository.save(DeliveryStatusHistoryEntity.builder()
                .deliveryId(delivery.getId())
                .fromStatus(null)
                .toStatus(DeliveryStatus.ACCEPTED)
                .attemptNumber(0)
                .occurredAt(Instant.now())
                .detail("fanned out from request " + delivery.getRequestId())
                .build());
    }

    /** States a delivery never leaves, and therefore the ones that stamp {@code settledAt}. */
    public static boolean isSettled(DeliveryStatus status) {
        return switch (status) {
            case SENT, DELIVERED, DEAD, SUPPRESSED, CANCELLED, COLLAPSED -> true;
            case ACCEPTED, PENDING, CLAIMED, FAILED, BATCHED -> false;
        };
    }
}
