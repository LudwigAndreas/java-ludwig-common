package ru.ludwigandreas.reconciliation.repository;

import org.springframework.data.domain.Pageable;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.reconciliation.entity.RemoteJobState;
import ru.ludwigandreas.reconciliation.entity.SyncRemoteJob;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Asynchronous remote jobs. */
public interface SyncRemoteJobRepository
        extends BaseRepository<SyncRemoteJob, UUID>, SyncRemoteJobRepositoryCustom {

    /**
     * Finds a job by the idempotency key committed before its submit call - how an ambiguous submit
     * is matched against the partner's listing of active jobs.
     *
     * @param idempotencyKey the key
     * @return the job, if this instance ever created a row for it
     */
    Optional<SyncRemoteJob> findByIdempotencyKey(String idempotencyKey);

    /**
     * Jobs of a task in any of the given states.
     *
     * @param taskName the task
     * @param states   the states
     * @return the jobs
     */
    List<SyncRemoteJob> findByTaskNameAndStateIn(String taskName, Collection<RemoteJobState> states);

    /**
     * How many jobs of a task are in any of the given states - the submit pass's own in-flight check,
     * separate from the quota's.
     *
     * @param taskName the task
     * @param states   the states
     * @return the count
     */
    long countByTaskNameAndStateIn(String taskName, Collection<RemoteJobState> states);

    /**
     * Non-terminal jobs whose lifetime has run out, for the expiry sweep.
     *
     * @param states the non-terminal states
     * @param now    the current instant
     * @param pageable the page, so one sweep cannot load an unbounded backlog
     * @return the expired jobs
     */
    List<SyncRemoteJob> findByStateInAndExpiresAtLessThan(Collection<RemoteJobState> states,
                                                          Instant now,
                                                          Pageable pageable);

    /**
     * Submits that have been in progress longer than their grace period, and are therefore ambiguous
     * rather than merely slow.
     *
     * @param cutoff  rows created before this instant
     * @param pageable the page
     * @return the ambiguous submits
     */
    List<SyncRemoteJob> findByStateAndCreatedAtLessThan(RemoteJobState state,
                                                        Instant cutoff,
                                                        Pageable pageable);
}
