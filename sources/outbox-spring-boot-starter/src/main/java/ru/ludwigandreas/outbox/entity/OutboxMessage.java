package ru.ludwigandreas.outbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A row in the {@code outbox_message} table: one unit of work written in the same transaction as the
 * business change it describes, and later claimed/dispatched by {@link ru.ludwigandreas.outbox.scheduler.OutboxProcessingService}.
 * <p>
 * Fields written at publish time ({@link #aggregateType} through {@link #traceId}) are never mutated
 * afterward ({@code updatable = false}); only the processing-lifecycle fields ({@link #status} and
 * below) change as the message moves through polling, dispatch, retry and (if exhausted) dead-lettering.
 */
@Getter
@Setter
@Entity
@Table(name = "outbox_message")
@EntityListeners(AuditingEntityListener.class)
public class OutboxMessage extends GeneratedEntity<UUID> {

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false, updatable = false)
    private int eventVersion = 1;

    /** Already-serialized JSON (see {@link ru.ludwigandreas.outbox.serialization.OutboxPayloadSerializer}), stored as {@code jsonb}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", updatable = false, columnDefinition = "jsonb")
    private Map<String, String> headers;

    /** Messages sharing a non-null ordering key are dispatched in creation order, best-effort (see the poll query). */
    @Column(name = "ordering_key", updatable = false)
    private String orderingKey;

    /** Resolved at publish time by the route resolver; identifies which {@link ru.ludwigandreas.outbox.dispatch.OutboxDispatcher} handles this row. */
    @Column(name = "transport", nullable = false, updatable = false)
    private String transport;

    /** Transport-specific target (Kafka topic name, REST endpoint key/URL, ...). */
    @Column(name = "destination", nullable = false, updatable = false)
    private String destination;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false, updatable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "trace_id", updatable = false)
    private String traceId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
