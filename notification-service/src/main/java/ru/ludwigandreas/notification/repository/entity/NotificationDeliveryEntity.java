package ru.ludwigandreas.notification.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import ru.ludwigandreas.db.core.entity.AuditedEntity;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;
import ru.ludwigandreas.odatafilter.annotation.FilterOperator;
import ru.ludwigandreas.odatafilter.annotation.FilterPolicy;
import ru.ludwigandreas.odatafilter.annotation.Filterable;

/**
 * One recipient, one channel, one unit of work - and the only place retry state exists.
 *
 * <p>This is the queue. It is <em>not</em> an outbox message and the outbox is deliberately not
 * reused for it: an outbox row exists to publish an event atomically with a local transaction and
 * has no notion of a channel, a priority lane, a schedule, a per-provider rate limit or a
 * suppression outcome. What is reused is the outbox's <em>mechanics</em> - a single native
 * {@code UPDATE ... FOR UPDATE SKIP LOCKED} claim, lease columns with a stale sweeper, exponential
 * backoff - because those are correct and hard to get right twice.
 *
 * <h2>Why not {@link AuditedEntity}</h2>
 *
 * <p>Because the claim step is a native {@code UPDATE} that Hibernate never sees, so
 * {@code @LastModifiedDate} would not fire for it and {@code updated_at} would report the last
 * <em>ORM</em> write rather than the last change - a timestamp that is wrong exactly when an
 * operator is trying to work out whether the queue is moving. The lifecycle instants below are
 * explicit and each one means one thing. {@code createdBy} is likewise absent: a delivery is written
 * by the fan-out, not by a caller, and the accountable principal is on the request.
 *
 * <h2>Behaviour at three replicas</h2>
 *
 * <p>{@code SKIP LOCKED} partitions the claim, so three pollers claim disjoint batches without
 * coordination and without blocking. A pod that is {@code SIGKILL}ed between claim and outcome
 * leaves rows {@code CLAIMED} with a stale {@code claimed_at}; the reclaim sweeper returns them.
 * Nothing here needs a distributed lock.
 */
@Entity
@Table(name = "notification_delivery")
@EntityListeners(AuditingEntityListener.class)
@FilterPolicy(maxDepth = 4, maxPageSize = 200, defaultPageSize = 25, maxNestedPropertyDepth = 1)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDeliveryEntity extends GeneratedEntity<UUID> {

    /**
     * The request this delivery fans out from.
     *
     * <p>A plain column with an index rather than a mapped {@code @ManyToOne}: the poller claims
     * deliveries by the thousand and must never trigger a parent load, and the admin API joins
     * explicitly when it wants the request. The database-level foreign key is declared in the
     * changelog with {@code ON DELETE CASCADE}, which is what keeps the retention purge to one
     * statement.
     */
    @Filterable(ops = {FilterOperator.EQ, FilterOperator.IN})
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN})
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 16)
    private ChannelKind channel;

    /**
     * The user id the caller addressed, when they addressed one.
     *
     * <p>Filterable only by notification admins. It is a pseudonymous subject rather than a name or
     * an address, but it still identifies a person, and support genuinely needs "show me this
     * user's notifications" - so it is gated by role rather than removed, and
     * {@link #recipientAddress} below is not exposed at all.
     */
    @Filterable(roles = "ROLE_NOTIFICATION_ADMIN", ops = {FilterOperator.EQ, FilterOperator.IN})
    @Column(name = "recipient_user_id", updatable = false, length = 255)
    private String recipientUserId;

    /**
     * Where it actually went: an email address, a chat id, a callback URL.
     *
     * <p>Deliberately carries no {@link Filterable}, so no {@code $filter} can reach it however the
     * query is spelled. It is direct personal data: filterable means enumerable, and an endpoint
     * that answers "does any delivery exist for this address?" is an address-validity oracle. It is
     * also never logged unmasked and never used as a metric tag, and the retention purge clears it.
     *
     * <p>Nullable, and both null cases are real: a delivery that never resolved to a destination
     * (recorded {@code DEAD} so that one unreachable recipient out of five is representable), and a
     * delivery whose address the retention purge has scrubbed. A {@code NOT NULL} column would have
     * forced a placeholder string for the first case, which is a value that looks like an address
     * and is not one.
     */
    @Column(name = "recipient_address", length = 512)
    private String recipientAddress;

    /**
     * The variables this delivery renders with: the request's, merged with anything the caller
     * addressed to this recipient specifically, plus the resolved recipient's own context.
     *
     * <p>Denormalized onto the delivery rather than read from the parent request at send time, for
     * two reasons. The poller runs at batch scale, and one extra select per delivery would double its
     * database traffic for data that cannot change after fan-out. And per-recipient variables are a
     * first-class feature - one request greeting five people by name is one request, not five - so a
     * delivery genuinely has its own variable set, not a copy of a shared one.
     *
     * <p>May contain personal data (a display name, an order reference), so the retention purge
     * clears it on the same schedule as the recipient address.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", nullable = false, columnDefinition = "jsonb")
    private String variables;

    /** Resolved at fan-out; decides which locale variant of the template renders. */
    @Column(name = "recipient_locale", nullable = false, updatable = false, length = 35)
    private String recipientLocale;

    /** IANA zone id. Quiet hours are evaluated in it, never in the server's zone. */
    @Column(name = "recipient_timezone", nullable = false, updatable = false, length = 64)
    private String recipientTimezone;

    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN,
            FilterOperator.STARTSWITH})
    @Column(name = "template_key", nullable = false, updatable = false, length = 128)
    private String templateKey;

    /**
     * Content hash of the template that produced this body, recorded at render time.
     *
     * <p>Templates are files and are hot-reloaded, so "which template said that?" cannot be answered
     * by looking at the directory later - the file has moved on. The hash is the version, and
     * {@code notification_template_revision} maps it back to a readable revision number.
     */
    @Column(name = "template_version", length = 64)
    private String templateVersion;

    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN})
    @Column(name = "category", nullable = false, updatable = false, length = 128)
    private String category;

    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE})
    @Enumerated(EnumType.STRING)
    @Column(name = "category_kind", nullable = false, updatable = false, length = 16)
    private CategoryKind categoryKind;

    /** Stored as an orderable weight - see {@link DeliveryPriorityConverter}. */
    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE, FilterOperator.GE, FilterOperator.LE})
    @Column(name = "priority", nullable = false)
    private DeliveryPriority priority;

    @Filterable(ops = {FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN})
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DeliveryStatus status;

    /**
     * Groups deliveries that collapse into one digest send: recipient + channel + digest window.
     *
     * <p>Null for everything that sends immediately, and indexed partially on that basis, so the
     * digest job scans only what is actually batched.
     */
    @Column(name = "digest_group", length = 255)
    private String digestGroup;

    /** The digest delivery that carries this one, once it has been collapsed into it. */
    @Column(name = "collapsed_into_id")
    private UUID collapsedIntoId;

    @Filterable(ops = {FilterOperator.EQ, FilterOperator.GE, FilterOperator.LE})
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** Copied from configuration at fan-out, so changing the limit cannot revive settled rows. */
    @Column(name = "max_attempts", nullable = false, updatable = false)
    private int maxAttempts;

    /**
     * The single column that decides due-ness: {@code max(scheduledAt, now)} at fan-out, then the
     * backoff after each retryable failure.
     *
     * <p>{@link #scheduledAt} is deliberately <em>not</em> part of the claim predicate. Folding both
     * into one column keeps the claim to a single range condition the partial index can drive, and
     * removes the class of bug where the two disagree.
     */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /** What the caller asked for, kept for display. Never read by the claim query. */
    @Filterable(ops = {FilterOperator.GE, FilterOperator.LE})
    @Column(name = "scheduled_at", updatable = false)
    private Instant scheduledAt;

    /** Lease timestamp. A {@code CLAIMED} row older than the stale timeout is swept back to PENDING. */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    /** Which poller instance holds the lease. Hostname plus a random suffix; see {@code LockOwner}. */
    @Column(name = "claimed_by", length = 255)
    private String claimedBy;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_failure_kind", length = 16)
    private FailureKind lastFailureKind;

    /** Why this delivery was suppressed, when it was. A reason code, not a sentence. */
    @Column(name = "suppression_reason", length = 128)
    private String suppressionReason;

    /** The provider's own id for the message, which is what an inbound receipt is matched on. */
    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Filterable(ops = {FilterOperator.GE, FilterOperator.LE})
    @Column(name = "sent_at")
    private Instant sentAt;

    @Filterable(ops = {FilterOperator.GE, FilterOperator.LE})
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    /** Copied from the request so the poller can re-bind it without loading the request. */
    @Column(name = "correlation_id", updatable = false, length = 128)
    private String correlationId;

    /** Copied from the request; drives the {@code TENANT} data scope on the admin API. */
    @Column(name = "tenant_id", updatable = false, length = 128)
    private String tenantId;

    /**
     * Per-delivery dedup key: request idempotency key + channel + recipient.
     *
     * <p>Uniquely indexed. This is the last line of defence against a double send - even if two
     * replicas both got past the request-level idempotency check, the second fan-out's insert
     * cannot create a second row for the same recipient-channel pair.
     */
    @Column(name = "dedup_key", updatable = false, length = 512)
    private String dedupKey;

    @Filterable(ops = {FilterOperator.GE, FilterOperator.LE}, sortable = true)
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
