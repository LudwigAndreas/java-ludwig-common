package ru.ludwigandreas.outbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * One recorded status transition of an {@link OutboxMessage}, written by
 * {@link ru.ludwigandreas.outbox.audit.PersistingOutboxAuditLogger} when
 * {@code ludwig.outbox.audit.persist-history} is enabled. Deliberately holds a plain
 * {@link #outboxMessageId} column with no foreign key to {@code outbox_message}: an FK would force a
 * key-share lock on the parent row on every history insert, which can needlessly contend with the
 * {@code PESSIMISTIC_WRITE} lock the poll query takes on that same row.
 */
@Getter
@Setter
@Entity
@Table(name = "outbox_status_history")
public class OutboxStatusHistory extends GeneratedEntity<UUID> {

    @Column(name = "outbox_message_id", nullable = false, updatable = false)
    private UUID outboxMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false)
    private OutboxStatus status;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "detail", updatable = false, columnDefinition = "text")
    private String detail;
}
