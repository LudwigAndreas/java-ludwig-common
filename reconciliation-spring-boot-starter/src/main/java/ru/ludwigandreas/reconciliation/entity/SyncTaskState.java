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
import ru.ludwigandreas.reconciliation.api.DemandTier;

import java.time.Instant;
import java.util.UUID;

/**
 * Where a task got to: the page cursor of an interrupted sweep, and the incremental watermark.
 *
 * <p>One row per task and tier. The tier is part of the identity because the hot and cold halves of a
 * task advance independently - a hot pass every thirty seconds and a cold sweep every four hours
 * sharing one watermark would have the fast one constantly declaring the slow one finished.
 *
 * <h2>Why the cursor is persisted rather than held in memory</h2>
 *
 * <p>Because the run it belongs to routinely does not finish in one process. A sweep of a large
 * catalogue spans many ticks and sometimes a deployment; an in-memory cursor means every restart
 * begins at page zero, re-reads everything already staged, and - if the sweep takes longer than the
 * interval between restarts - never completes at all, while looking busy the whole time.
 */
@Getter
@Setter
@Entity
@Table(name = "sync_task_state")
public class SyncTaskState extends GeneratedEntity<UUID> {

    @Column(name = "task_name", nullable = false, updatable = false)
    private String taskName;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, updatable = false)
    private DemandTier tier = DemandTier.HOT;

    /** Opaque page token of a sweep that has not finished; null when no sweep is in progress. */
    @Column(name = "cursor", columnDefinition = "text")
    private String cursor;

    /**
     * Newest external change timestamp this task has fully processed.
     *
     * <p>Advanced only when a pass completes, never per page. Advancing it mid-sweep would mean a
     * crash leaves the watermark past records that were never staged, and those records are then
     * skipped forever - a silent, permanent gap that no metric shows.
     */
    @Column(name = "watermark")
    private Instant watermark;

    /** When the current sweep started, so an abandoned cursor can be recognised as stale. */
    @Column(name = "sweep_started_at")
    private Instant sweepStartedAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_run_id")
    private UUID lastRunId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
