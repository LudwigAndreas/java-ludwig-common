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
 * A task waiting for a slot of a quota, and since when.
 *
 * <h2>Why waiting is recorded rather than just retried</h2>
 *
 * <p>Two reasons, and both are operational.
 *
 * <p>First, <b>fairness</b>. Without a queue, "who gets the next free slot" is decided by whichever
 * scheduler tick happens to land first, and a task on a thirty-second cadence wins essentially every
 * race against a task on a five-minute one. The slower task can then starve indefinitely while every
 * individual acquisition looks perfectly correct. Granting slots in arrival order costs one row and
 * removes the whole failure mode.
 *
 * <p>Second, <b>visibility</b>. The age of the oldest waiter is the one number that says whether a
 * quota is sized correctly. A saturation ratio pinned at 1.0 looks identical whether the queue is
 * emptying in seconds or has not moved in an hour.
 *
 * <p>Waiters heartbeat for the same reason leases do: an instance that died while queued must not
 * hold the head of the line forever.
 */
@Getter
@Setter
@Entity
@Table(name = "sync_quota_waiter")
public class QuotaWaiter extends GeneratedEntity<UUID> {

    @Column(name = "quota_name", nullable = false, updatable = false)
    private String quotaName;

    @Column(name = "task_name", nullable = false, updatable = false)
    private String taskName;

    @Column(name = "owner_instance", nullable = false, updatable = false)
    private String ownerInstance;

    /** Set once, on first enqueue, and never refreshed - it is the position in the queue. */
    @Column(name = "enqueued_at", nullable = false, updatable = false)
    private Instant enqueuedAt;

    @Column(name = "heartbeat_at", nullable = false)
    private Instant heartbeatAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
