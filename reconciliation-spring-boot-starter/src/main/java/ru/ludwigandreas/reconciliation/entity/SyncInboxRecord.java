package ru.ludwigandreas.reconciliation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * One external record, fetched and written down, waiting to be applied.
 *
 * <h2>Why fetching and applying are not one transaction</h2>
 *
 * <p>Doing them together is what makes these jobs brittle. One bad record poisons the run, so a
 * single unmappable response stops every other record behind it; and a retry re-hits the partner for
 * records already in hand, which is slow, rude, and occasionally billable. Writing the fetch down
 * first buys four things that are hard to get any other way: per-item retry without another call, a
 * replayable audit trail of exactly what the partner said, per-record dead-lettering rather than
 * per-run failure, and an apply cadence that does not have to match the fetch cadence.
 *
 * <p>One table for every task rather than one per task, keyed by {@code task_name}. The alternative
 * means a migration per integration and a separate backlog metric per integration, for no benefit -
 * the rows have identical shape and are always queried within one task.
 */
@Getter
@Setter
@Entity
@Table(name = "sync_inbox_record")
public class SyncInboxRecord extends GeneratedEntity<UUID> {

    @Column(name = "task_name", nullable = false, updatable = false)
    private String taskName;

    /** What this row is a record of; decides which pass claims it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private SyncRecordKind kind = SyncRecordKind.RECORD;

    /** The external system's address for this record, rendered by the task's {@code KeyCodec}. */
    @Column(name = "correlation_key", nullable = false, updatable = false)
    private String correlationKey;

    /** Exactly what the partner returned, serialized; never rewritten, so the audit trail is the truth. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", updatable = false, columnDefinition = "jsonb")
    private String payload;

    /** The partner's version token, if it publishes one. */
    @Column(name = "external_version", updatable = false)
    private String externalVersion;

    /** When the partner says the record last changed, if it says. */
    @Column(name = "external_timestamp", updatable = false)
    private Instant externalTimestamp;

    /**
     * Hash of the normalized payload. The idempotency short-circuit: a staged record whose hash equals
     * the newest already-applied hash for the same key is settled as {@code UNCHANGED} without a
     * write, without {@code updated_at} churn and without an audit row.
     */
    @Column(name = "payload_hash", updatable = false)
    private String payloadHash;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SyncRecordStatus status = SyncRecordStatus.STAGED;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false, updatable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "locked_by")
    private String lockedBy;

    /** The run that fetched this record, so one partner interaction is traceable end to end. */
    @Column(name = "run_id", updatable = false)
    private UUID runId;

    /** The asynchronous job this record was collected from, when it came from one. */
    @Column(name = "job_id", updatable = false)
    private UUID jobId;

    /** Correlation id of the unit of work that fetched it, from the observability starter. */
    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
