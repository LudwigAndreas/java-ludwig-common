package ru.ludwigandreas.reconciliation.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.reconciliation.entity.SyncInboxRecord;
import ru.ludwigandreas.reconciliation.entity.SyncRecordKind;
import ru.ludwigandreas.reconciliation.entity.SyncRecordStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Staged records.
 *
 * <p>Requires {@code @EnableJpaRepositories(repositoryBaseClass =
 * ru.ludwigandreas.db.core.repository.BaseRepositoryImpl.class)} on the consuming application, the
 * same requirement {@code db-core} imposes on every {@link BaseRepository} subinterface. The module's
 * own autoconfiguration declares it for this package, so a consumer does not have to.
 */
public interface SyncInboxRecordRepository
        extends BaseRepository<SyncInboxRecord, UUID>, SyncInboxRecordRepositoryCustom {

    /**
     * The most recently settled record for a key, which is what both apply-time guard rails ask
     * about: its stamp answers "is what just arrived older than what we already applied?", and its
     * hash answers "is this the same payload we already applied?".
     *
     * @param taskName       the task
     * @param correlationKey the key
     * @param pageable       always a single-row page; Spring Data has no {@code Optional} form of a
     *                       {@code LIMIT 1} query with a sort
     * @return the newest successfully settled record, or an empty list
     */
    @Query("select r from SyncInboxRecord r where r.taskName = :taskName "
            + "and r.correlationKey = :correlationKey "
            + "and r.status in (ru.ludwigandreas.reconciliation.entity.SyncRecordStatus.APPLIED, "
            + "ru.ludwigandreas.reconciliation.entity.SyncRecordStatus.UNCHANGED) "
            + "order by r.settledAt desc")
    List<SyncInboxRecord> findNewestSettled(@Param("taskName") String taskName,
                                            @Param("correlationKey") String correlationKey,
                                            Pageable pageable);

    /**
     * Correlation keys the fetch pass should leave alone this run: a failure that is not due to be
     * retried yet, or one whose budget is spent.
     *
     * <p>Read once per run rather than per key. Without this, a key the partner reliably chokes on is
     * refetched on every run forever, at the task's cadence, indistinguishable in the metrics from
     * work that is making progress.
     *
     * @param taskName the task
     * @param now      the current instant
     * @return the keys to skip
     */
    @Query("select r.correlationKey from SyncInboxRecord r where r.taskName = :taskName "
            + "and r.kind = ru.ludwigandreas.reconciliation.entity.SyncRecordKind.FETCH_FAILURE "
            + "and (r.status = ru.ludwigandreas.reconciliation.entity.SyncRecordStatus.QUARANTINED "
            + "  or (r.status = ru.ludwigandreas.reconciliation.entity.SyncRecordStatus.FAILED "
            + "      and r.nextAttemptAt > :now))")
    List<String> findSuppressedKeys(@Param("taskName") String taskName, @Param("now") Instant now);

    /**
     * The outstanding fetch-failure row for a key, so a retry continues that key's budget rather than
     * starting a new one, and so a later success can settle it.
     *
     * @param taskName the task
     * @param key      the correlation key
     * @return the row, if this key has an unsettled fetch failure
     */
    @Query("select r from SyncInboxRecord r where r.taskName = :taskName and r.correlationKey = :key "
            + "and r.kind = ru.ludwigandreas.reconciliation.entity.SyncRecordKind.FETCH_FAILURE "
            + "and r.status in (ru.ludwigandreas.reconciliation.entity.SyncRecordStatus.FAILED, "
            + "ru.ludwigandreas.reconciliation.entity.SyncRecordStatus.QUARANTINED)")
    List<SyncInboxRecord> findOpenFetchFailures(@Param("taskName") String taskName, @Param("key") String key);

    /**
     * How many rows of a task and kind are in any of the given statuses.
     *
     * @param taskName the task
     * @param kind     the row kind
     * @param statuses the statuses
     * @return the count
     */
    long countByTaskNameAndKindAndStatusIn(String taskName, SyncRecordKind kind,
                                           Collection<SyncRecordStatus> statuses);

    /**
     * Crash recovery for an instance that claimed records and died before settling them.
     *
     * <p>{@code @Transactional} is required and is not redundant: a {@code @Modifying} query declared
     * on a repository interface inherits none of {@code SimpleJpaRepository}'s transaction attributes,
     * and without it this fails with {@code TransactionRequiredException} inside a scheduler that
     * catches and logs - so records are never reclaimed and the only evidence is a recurring stack
     * trace.
     *
     * @param staleBefore records locked before this instant are presumed abandoned
     * @return how many were reclaimed
     */
    @Transactional
    @Modifying
    @Query("update SyncInboxRecord r "
            + "set r.status = ru.ludwigandreas.reconciliation.entity.SyncRecordStatus.STAGED, "
            + "r.lockedAt = null, r.lockedBy = null "
            + "where r.status = ru.ludwigandreas.reconciliation.entity.SyncRecordStatus.PROCESSING "
            + "and r.lockedAt < :staleBefore")
    int reclaimStale(@Param("staleBefore") Instant staleBefore);

    /**
     * How many records of a task are in any of the given statuses.
     *
     * @param taskName the task
     * @param statuses the statuses
     * @return the count
     */
    long countByTaskNameAndStatusIn(String taskName, Collection<SyncRecordStatus> statuses);

    /**
     * When the oldest record of a task in any of the given statuses was received - the numerator of
     * the staged-backlog age gauge.
     *
     * @param taskName the task
     * @param statuses the statuses
     * @return the instant, or null when there are none
     */
    @Query("select min(r.receivedAt) from SyncInboxRecord r "
            + "where r.taskName = :taskName and r.status in :statuses")
    Instant findOldestReceivedAt(@Param("taskName") String taskName,
                                 @Param("statuses") Collection<SyncRecordStatus> statuses);

    /**
     * Records of a task in a given status, newest first - the actuator's quarantine listing.
     *
     * @param taskName the task
     * @param status   the status
     * @param pageable the page
     * @return the records
     */
    List<SyncInboxRecord> findByTaskNameAndStatusOrderByReceivedAtDesc(String taskName,
                                                                       SyncRecordStatus status,
                                                                       Pageable pageable);

    /**
     * Returns quarantined records of a task to the queue, resetting their attempt counter.
     *
     * <p>Resetting rather than continuing is deliberate: an operator requeues because something was
     * fixed - a mapping, a partner's data, a deployment - so the record deserves a whole budget
     * against the new conditions rather than the one attempt its old budget had left.
     *
     * @param taskName the task
     * @param now      when the requeued records become due
     * @return how many were requeued
     */
    @Transactional
    @Modifying
    @Query("update SyncInboxRecord r "
            + "set r.status = ru.ludwigandreas.reconciliation.entity.SyncRecordStatus.STAGED, "
            + "r.attempts = 0, r.nextAttemptAt = :now, r.settledAt = null, r.lockedAt = null, r.lockedBy = null "
            + "where r.taskName = :taskName "
            + "and r.status = ru.ludwigandreas.reconciliation.entity.SyncRecordStatus.QUARANTINED")
    int requeueQuarantined(@Param("taskName") String taskName, @Param("now") Instant now);

    /**
     * Returns the records a remote job produced to the queue, for when that job expired or failed
     * part-way through collection.
     *
     * @param jobId the job
     * @param now   when the requeued records become due
     * @return how many were requeued
     */
    @Transactional
    @Modifying
    @Query("update SyncInboxRecord r "
            + "set r.status = ru.ludwigandreas.reconciliation.entity.SyncRecordStatus.STAGED, "
            + "r.attempts = 0, r.nextAttemptAt = :now, r.settledAt = null, r.lockedAt = null, r.lockedBy = null "
            + "where r.jobId = :jobId "
            + "and r.status = ru.ludwigandreas.reconciliation.entity.SyncRecordStatus.QUARANTINED")
    int requeueForJob(@Param("jobId") UUID jobId, @Param("now") Instant now);
}
