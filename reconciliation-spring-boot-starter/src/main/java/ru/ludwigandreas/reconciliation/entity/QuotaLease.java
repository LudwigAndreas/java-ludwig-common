package ru.ludwigandreas.reconciliation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * One occupied slot of a partner-scoped quota.
 *
 * <h2>Why the database and not Resilience4j</h2>
 *
 * <p>A bulkhead is per-JVM. "At most five concurrent exports against this partner" is a statement
 * about the cluster, and three replicas each honouring a limit of five produce fifteen. The counting
 * has to happen somewhere all of them can see, and the database is already there.
 *
 * <h2>Why a lease and not a counter</h2>
 *
 * <p>Because holders die. A decrement that only happens when the holder releases means every crash
 * permanently shrinks the quota by one, and after enough crashes the integration stops entirely -
 * with no error anywhere, because from the inside it looks exactly like a busy partner. The lease
 * expires unless it is renewed, so a dead holder's slot comes back on its own.
 *
 * <p>The counterpart rule lives in the reclaim path, not here: an expired lease does not mean the
 * remote work stopped. Reclaiming it without checking is how a partner's stated limit gets exceeded.
 */
@Getter
@Setter
@Entity
@Table(name = "sync_quota_lease")
public class QuotaLease extends GeneratedEntity<UUID> {

    @Column(name = "quota_name", nullable = false, updatable = false)
    private String quotaName;

    /** Which task is using the slot, for the per-task view in the actuator endpoint. */
    @Column(name = "task_name", nullable = false, updatable = false)
    private String taskName;

    @Column(name = "owner_instance", nullable = false)
    private String ownerInstance;

    /** The remote job this slot is held for, when it is held for one. */
    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "acquired_at", nullable = false, updatable = false)
    private Instant acquiredAt;

    @Column(name = "heartbeat_at", nullable = false)
    private Instant heartbeatAt;

    /** The only thing that decides whether this slot is occupied. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Hard stop for this slot, independent of renewals.
     *
     * <p>A lease that is being renewed forever by a job that will never finish is a leaked slot that
     * heartbeating cannot detect, because the heartbeat is working perfectly. This is what bounds it.
     */
    @Column(name = "max_lifetime_at", nullable = false)
    private Instant maxLifetimeAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
