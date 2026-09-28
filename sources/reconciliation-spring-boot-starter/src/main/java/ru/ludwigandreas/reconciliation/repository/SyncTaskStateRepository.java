package ru.ludwigandreas.reconciliation.repository;

import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.entity.SyncTaskState;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Per-task, per-tier cursor and watermark. */
public interface SyncTaskStateRepository extends BaseRepository<SyncTaskState, UUID> {

    /**
     * The state row for a task and tier.
     *
     * @param taskName the task
     * @param tier     the tier
     * @return the row, or empty before the task's first run
     */
    Optional<SyncTaskState> findByTaskNameAndTier(String taskName, DemandTier tier);

    /**
     * Every state row for a task - both tiers - for the actuator's task listing.
     *
     * @param taskName the task
     * @return the rows
     */
    List<SyncTaskState> findByTaskName(String taskName);
}
