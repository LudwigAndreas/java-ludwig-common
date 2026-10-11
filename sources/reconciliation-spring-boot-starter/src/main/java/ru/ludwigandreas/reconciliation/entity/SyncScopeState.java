package ru.ludwigandreas.reconciliation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * Where an enumeration task got to for one scope.
 *
 * <p>One row per task and scope. Everything a walk needs to resume and everything the planner needs
 * to decide whether the scope is due lives here, per scope, because scopes advance independently: one
 * added today needs a full first walk however far the others have got.
 */
@Getter
@Setter
@Entity
@Table(name = "sync_scope_state")
public class SyncScopeState extends GeneratedEntity<UUID> {

    @Column(name = "task_name", nullable = false, updatable = false)
    private String taskName;

    @Column(name = "scope_key", nullable = false, updatable = false)
    private String scopeKey;

    /**
     * How many full walks of this scope have completed.
     *
     * <p>A full walk in progress stamps the members it sees with {@code generation + 1}, and this
     * counter moves only when that walk completes. Counting at the start instead would let an
     * abandoned walk count as a miss, and "absent from N consecutive completed walks" would no longer
     * be what the sweep measures.
     */
    @Column(name = "generation", nullable = false)
    private long generation;

    /** The kind of the walk in progress; null when none is. */
    @Enumerated(EnumType.STRING)
    @Column(name = "walk_kind")
    private ScopeWalkKind walkKind;

    /** Opaque page token of the walk in progress; null at the first page. */
    @Column(name = "walk_cursor", columnDefinition = "text")
    private String walkCursor;

    @Column(name = "walk_started_at")
    private Instant walkStartedAt;

    /** Consecutive failed attempts to fetch or apply a page, for the scope's own backoff. */
    @Column(name = "walk_attempts", nullable = false)
    private int walkAttempts;

    /**
     * Newest external change timestamp a completed walk has seen.
     *
     * <p>Set from {@link #highWater} only when a walk completes. Advancing it per page would leave an
     * interrupted walk with a watermark past pages it never read.
     */
    @Column(name = "watermark")
    private Instant watermark;

    /** Newest external change timestamp the walk in progress has seen so far. */
    @Column(name = "high_water")
    private Instant highWater;

    /** When a full walk last completed; null until the first one does. */
    @Column(name = "last_full_at")
    private Instant lastFullAt;

    @Column(name = "last_incremental_at")
    private Instant lastIncrementalAt;

    @Column(name = "next_due_at", nullable = false)
    private Instant nextDueAt;

    /** Set while a sweep is withheld for an operator; null otherwise. */
    @Column(name = "sweep_held_at")
    private Instant sweepHeldAt;

    @Column(name = "sweep_held_count", nullable = false)
    private int sweepHeldCount;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "last_run_id")
    private UUID lastRunId;

    /** When discovery first saw the scope; what a scope that has never been listed is late since. */
    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
