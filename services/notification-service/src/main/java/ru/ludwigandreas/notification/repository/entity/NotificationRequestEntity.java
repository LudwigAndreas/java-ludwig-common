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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.ludwigandreas.db.core.entity.AuditedEntity;

/**
 * What one caller asked for: a template, a set of recipients, a set of channels and a variable map.
 *
 * <p>This aggregate is deliberately inert. It records the ask and nothing about how the ask went -
 * no attempt counter, no {@code lastError}, no retry schedule. All of that belongs to
 * {@link NotificationDeliveryEntity}, one row per recipient per channel, because a request to three
 * recipients where one mailbox bounces has no single status. Collapsing the two is the modelling
 * mistake that makes partial failure unrepresentable, and it is unrecoverable once history exists.
 *
 * <p>Extends db-core's {@link AuditedEntity}: a request is application-owned data created by an
 * authenticated caller, so a generated id, {@code createdBy}/{@code createdAt} (which is also what
 * the {@code OWNER} data scope compares against) and {@code @Version} are all wanted. The version
 * matters specifically for the {@code ACCEPTED -> FANNED_OUT} transition, which two concurrent
 * redeliveries of the same Kafka record can race on.
 */
@Entity
@Table(name = "notification_request")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRequestEntity extends AuditedEntity<UUID> {

    /**
     * The caller's own idempotency key, scoped by {@link #source}.
     *
     * <p>Not itself unique-constrained here: the dedup window lives in
     * {@code notification_idempotency}, which carries an expiry so a key can be reused after the
     * retention window rather than being reserved forever. This column is the back-reference an
     * operator follows from a dedup hit to the request that won.
     */
    @Column(name = "idempotency_key", updatable = false, length = 255)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 16)
    private NotificationSource source;

    /** Template family, e.g. {@code password-reset}. The channel and locale complete the address. */
    @Column(name = "template_key", nullable = false, updatable = false, length = 128)
    private String templateKey;

    /** Business grouping the recipient's preferences are expressed against, e.g. {@code security}. */
    @Column(name = "category", nullable = false, updatable = false, length = 128)
    private String category;

    /** Whether the recipient is allowed to decline this category. See {@link CategoryKind}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category_kind", nullable = false, updatable = false, length = 16)
    private CategoryKind categoryKind;

    @Column(name = "priority", nullable = false, updatable = false)
    private DeliveryPriority priority;

    /**
     * The template variables, verbatim as the caller sent them.
     *
     * <p>Kept as {@code jsonb} rather than shredded into columns because this service has no schema
     * for them - they belong to whichever template renders them - and because an operator
     * reproducing a render needs exactly the bytes that were submitted.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String variables;

    /**
     * The recipients as asked for, before resolution: user ids, literal addresses, or both.
     *
     * <p>Redundant with the delivery rows on the happy path, and the point is the unhappy one: when
     * every recipient fails to resolve there are no delivery rows to read the ask back out of, and
     * "who was this supposed to go to?" would be unanswerable.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requested_recipients", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String requestedRecipients;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RequestStatus status;

    /** When the caller wants the first attempt made; {@code null} means immediately. */
    @Column(name = "scheduled_at", updatable = false)
    private Instant scheduledAt;

    /** Owning organization, copied from the caller's principal. Drives the {@code TENANT} scope. */
    @Column(name = "tenant_id", updatable = false, length = 128)
    private String tenantId;

    /**
     * The correlation id of the inbound Kafka record or HTTP request.
     *
     * <p>Carried on the row rather than only in the log line, because the delivery happens minutes
     * later on a different thread in a different pod: the poller reads it back off the delivery and
     * re-binds it, which is what keeps one business operation's log lines joinable end to end.
     */
    @Column(name = "correlation_id", updatable = false, length = 128)
    private String correlationId;

    /** Sampled trace the request arrived on, when there was one. Diagnostics only. */
    @Column(name = "trace_id", updatable = false, length = 64)
    private String traceId;

    /** Why nothing was enqueued, when {@link #status} is {@link RequestStatus#REJECTED}. */
    @Column(name = "rejection_reason", length = 512)
    private String rejectionReason;
}
