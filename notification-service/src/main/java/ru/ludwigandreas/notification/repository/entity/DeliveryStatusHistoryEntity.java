package ru.ludwigandreas.notification.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

/**
 * One recorded transition of a {@link NotificationDeliveryEntity}, mirroring the outbox module's
 * {@code OutboxStatusHistory}.
 *
 * <p>The delivery row carries only its current state, and current state answers none of the
 * questions an operator actually asks: how long did it sit PENDING, how many times did it fail
 * before it went DEAD, was it suppressed before or after the recipient unsubscribed. The trail is
 * what makes those answerable, and it is append-only - every column is {@code updatable = false}.
 *
 * <p>Deliberately holds a plain {@link #deliveryId} with no mapped association and, as in the outbox
 * module, no database foreign key either: an FK would take a {@code FOR KEY SHARE} lock on the
 * delivery row on every history insert, and the delivery row is the single hottest row in this
 * service. The retention purge deletes history by age instead of relying on a cascade.
 */
@Entity
@Table(name = "notification_delivery_status_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryStatusHistoryEntity extends GeneratedEntity<UUID> {

    @Column(name = "delivery_id", nullable = false, updatable = false)
    private UUID deliveryId;

    /** The state being left. Null for the very first record, which has no predecessor. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false, length = 16)
    private DeliveryStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 16)
    private DeliveryStatus toStatus;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /**
     * Why, in one short operator-facing line.
     *
     * <p>Never the rendered body and never the recipient's address: this table outlives the delivery
     * row it describes (it is purged on a longer schedule, because the trail is the audit record),
     * so anything personal written here outlives the retention promise made about the delivery.
     */
    @Column(name = "detail", updatable = false, columnDefinition = "text")
    private String detail;
}
