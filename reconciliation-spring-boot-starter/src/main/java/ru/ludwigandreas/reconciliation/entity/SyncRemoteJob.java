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
 * An asynchronous job running on a partner's side, and everything needed to pick it back up.
 *
 * <h2>The write order, which is not negotiable</h2>
 *
 * <ol>
 *   <li>{@code INSERT} this row with {@link RemoteJobState#PENDING_SUBMIT} and a fresh
 *       {@link #idempotencyKey}, <b>acquire the quota lease</b>, and commit.</li>
 *   <li>Submit, passing the idempotency key when the partner honours one.</li>
 *   <li>{@code UPDATE} to {@link RemoteJobState#SUBMITTED} with the handle, and commit.</li>
 * </ol>
 *
 * <p>If the POST succeeds and the instance dies before step 3, a naive restart resubmits - burning a
 * quota slot and possibly a billable remote job that is already running. Step 1 is what leaves
 * evidence; the {@code PENDING_SUBMIT} row is <em>ambiguous, not lost</em>, and the task's
 * {@code on-ambiguous-submit} policy says what to do about it. There is no safe default across
 * partners, which is why that setting is required for any task using {@code shape: async-job}.
 */
@Getter
@Setter
@Entity
@Table(name = "sync_remote_job")
public class SyncRemoteJob extends GeneratedEntity<UUID> {

    @Column(name = "task_name", nullable = false, updatable = false)
    private String taskName;

    /** Committed before the submit call, so a repeat can be recognised by the partner and by us. */
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    /** The partner's handle, rendered by the fetcher's handle codec. Null until the submit returns. */
    @Column(name = "external_handle")
    private String externalHandle;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private RemoteJobState state = RemoteJobState.PENDING_SUBMIT;

    /**
     * The correlation keys this job covers, as a JSON array.
     *
     * <p>Held on the job rather than derived, because it is what has to be requeued when the job
     * expires or fails - and by then the demand query that produced it may well answer differently.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "demand_keys", updatable = false, columnDefinition = "jsonb")
    private String demandKeys;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "poll_attempts", nullable = false)
    private int pollAttempts;

    @Column(name = "next_poll_at")
    private Instant nextPollAt;

    /** When this job stops being worth waiting for, from the task's {@code job.max-lifetime}. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Where collection got to; lets a large result resume rather than restart. */
    @Column(name = "collect_cursor", columnDefinition = "text")
    private String collectCursor;

    /** The quota lease held for this job's whole life, released only when it settles. */
    @Column(name = "quota_lease_id")
    private UUID quotaLeaseId;

    /**
     * Which instance is currently driving this job.
     *
     * <p>Rewritten on adoption. A restarted pod does not resubmit: it claims the existing row, and the
     * remote job keeps running under a new owner that resumes heartbeating the existing lease.
     */
    @Column(name = "owner_instance")
    private String ownerInstance;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
