package ru.ludwigandreas.notification.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.JpaBaseEntity;

/**
 * A leased, cluster-wide mutex - the second capability the platform does not have yet.
 *
 * <p>The delivery poller does not need one: {@code SKIP LOCKED} already partitions the queue between
 * replicas. The maintenance jobs do. A digest run that executes on all three replicas sends three
 * digests; a retention purge that does is merely wasteful; a suppression compaction that does can
 * interleave into a corrupt result. Those jobs are exactly-once-per-schedule or they are wrong.
 *
 * <p>Why a table and not {@code pg_try_advisory_lock}: an advisory lock is held by the <em>session</em>,
 * and with HikariCP the session goes back to the pool the moment the statement finishes. Holding one
 * across a multi-minute digest run would mean pinning a pooled connection for the duration and
 * trusting that nothing in the stack quietly returned it - and an advisory lock survives no pod
 * crash detection at all, it simply vanishes with the connection, mid-job. A row with an explicit
 * {@link #expiresAt} lease is inspectable, survives the connection, and fails over on a fixed,
 * configured timeout rather than an accidental one.
 *
 * <p>The id is the lock's name, assigned by the caller, so acquisition is one upsert on the primary
 * key rather than a lookup followed by an insert.
 */
@Entity
@Table(name = "notification_lock")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistributedLockEntity extends JpaBaseEntity<String> {

    /** The instance currently holding it - hostname plus a random suffix. */
    @Column(name = "owner", nullable = false, length = 255)
    private String owner;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    /**
     * When the lease lapses and another replica may take the lock.
     *
     * <p>Renewed by a heartbeat while the job runs, so a job that legitimately outlives its lease is
     * not overtaken. A job that stops renewing - because its pod died - loses the lock within one
     * lease period, which is the whole failover mechanism.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
