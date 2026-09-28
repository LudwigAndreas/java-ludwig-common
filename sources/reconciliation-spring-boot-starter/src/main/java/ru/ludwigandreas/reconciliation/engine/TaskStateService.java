package ru.ludwigandreas.reconciliation.engine;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.entity.SyncTaskState;
import ru.ludwigandreas.reconciliation.repository.SyncTaskStateRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and advances a task's cursor and watermark.
 *
 * <p>Every write here is {@code REQUIRES_NEW}, and that is the whole design: a checkpoint has to
 * survive the failure of the run that wrote it. Checkpointing inside the run's transaction would mean
 * a sweep that staged forty pages and then failed on the forty-first rolls back its cursor to page
 * zero along with nothing else - the staged pages are already committed - so the next run re-fetches
 * and re-stages all forty. The point of a checkpoint is to be a fact, not a proposal.
 */
public class TaskStateService {

    private final SyncTaskStateRepository repository;

    /**
     * Creates the service.
     *
     * @param repository the state table
     */
    public TaskStateService(SyncTaskStateRepository repository) {
        this.repository = repository;
    }

    /**
     * The task's state for a tier, if it has run before.
     *
     * @param taskName the task
     * @param tier     the tier
     * @return the state
     */
    @Transactional(readOnly = true)
    public Optional<SyncTaskState> find(String taskName, DemandTier tier) {
        return repository.findByTaskNameAndTier(taskName, tier);
    }

    /**
     * The cursor a paged sweep should resume from, or empty to start at the beginning.
     *
     * @param taskName the task
     * @param tier     the tier
     * @return the cursor
     */
    @Transactional(readOnly = true)
    public Optional<String> cursor(String taskName, DemandTier tier) {
        return find(taskName, tier).map(SyncTaskState::getCursor).filter(cursor -> !cursor.isBlank());
    }

    /**
     * The watermark an incremental task should ask for changes since.
     *
     * @param taskName the task
     * @param tier     the tier
     * @return the watermark, or empty on the first run
     */
    @Transactional(readOnly = true)
    public Optional<Instant> watermark(String taskName, DemandTier tier) {
        return find(taskName, tier).map(SyncTaskState::getWatermark);
    }

    /**
     * Records where a sweep got to.
     *
     * @param taskName the task
     * @param tier     the tier
     * @param cursor   the next page's token, or null when the sweep finished
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void checkpoint(String taskName, DemandTier tier, String cursor) {
        SyncTaskState state = loadOrCreate(taskName, tier);
        state.setCursor(cursor);
        if (cursor != null && state.getSweepStartedAt() == null) {
            state.setSweepStartedAt(Instant.now());
        }
        if (cursor == null) {
            state.setSweepStartedAt(null);
        }
        state.setUpdatedAt(Instant.now());
        repository.save(state);
    }

    /**
     * Advances the watermark, and records that a pass completed.
     *
     * <p>Called only when a pass completes, never per page. Advancing mid-sweep would leave a crash
     * with a watermark past records that were never staged, and those records are then skipped forever
     * - a silent, permanent gap that no metric shows. The value never moves backwards, so a task whose
     * partner briefly reports an older change time does not re-sweep history.
     *
     * @param taskName  the task
     * @param tier      the tier
     * @param watermark the newest external change timestamp fully processed, or null to leave it
     * @param runId     the run that completed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeRun(String taskName, DemandTier tier, Instant watermark, UUID runId) {
        SyncTaskState state = loadOrCreate(taskName, tier);
        if (watermark != null && (state.getWatermark() == null || watermark.isAfter(state.getWatermark()))) {
            state.setWatermark(watermark);
        }
        state.setLastRunAt(Instant.now());
        state.setLastRunId(runId);
        state.setUpdatedAt(Instant.now());
        repository.save(state);
    }

    /**
     * Resets a task's cursor and watermark - an operator action, for when a partner has invalidated a
     * cursor or a backfill has to be redone.
     *
     * @param taskName the task
     * @param tier     the tier
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset(String taskName, DemandTier tier) {
        SyncTaskState state = loadOrCreate(taskName, tier);
        state.setCursor(null);
        state.setWatermark(null);
        state.setSweepStartedAt(null);
        state.setUpdatedAt(Instant.now());
        repository.save(state);
    }

    private SyncTaskState loadOrCreate(String taskName, DemandTier tier) {
        return repository.findByTaskNameAndTier(taskName, tier).orElseGet(() -> {
            SyncTaskState created = new SyncTaskState();
            created.setTaskName(taskName);
            created.setTier(tier);
            created.setUpdatedAt(Instant.now());
            return created;
        });
    }
}
