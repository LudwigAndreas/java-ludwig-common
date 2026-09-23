package ru.ludwigandreas.reconciliation.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.reconciliation.entity.QuotaWaiter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The queue in front of each quota, which is what makes slot granting fair and waiting visible. */
public interface QuotaWaiterRepository extends BaseRepository<QuotaWaiter, UUID> {

    /**
     * This instance's queue entry for a quota and task, if it is already queued.
     *
     * @param quotaName     the quota
     * @param taskName      the task
     * @param ownerInstance this instance
     * @return the entry
     */
    Optional<QuotaWaiter> findByQuotaNameAndTaskNameAndOwnerInstance(String quotaName,
                                                                     String taskName,
                                                                     String ownerInstance);

    /**
     * Live queue entries for a quota, oldest first.
     *
     * <p>Filtered by heartbeat rather than by presence, for the same reason leases are: an instance
     * that died while queued must not hold the head of the line forever, which under FIFO would stop
     * the quota granting anything at all.
     *
     * @param quotaName the quota
     * @param aliveAfter entries heartbeated since this instant count as live
     * @param pageable  the page
     * @return the entries, oldest enqueue first
     */
    @Query("select w from QuotaWaiter w where w.quotaName = :quotaName and w.heartbeatAt >= :aliveAfter "
            + "order by w.enqueuedAt asc, w.id asc")
    List<QuotaWaiter> findLiveQueue(@Param("quotaName") String quotaName,
                                    @Param("aliveAfter") Instant aliveAfter,
                                    Pageable pageable);

    /**
     * When the longest-waiting live entry joined the queue - the numerator of the oldest-waiter gauge,
     * which is the one number that says whether a quota is sized correctly.
     *
     * @param quotaName  the quota
     * @param aliveAfter entries heartbeated since this instant count as live
     * @return the instant, or null when nobody is waiting
     */
    @Query("select min(w.enqueuedAt) from QuotaWaiter w "
            + "where w.quotaName = :quotaName and w.heartbeatAt >= :aliveAfter")
    Instant findOldestEnqueuedAt(@Param("quotaName") String quotaName,
                                 @Param("aliveAfter") Instant aliveAfter);

    /**
     * Removes queue entries whose instance has stopped heartbeating.
     *
     * @param aliveBefore entries last heartbeated before this instant are removed
     * @return how many were removed
     */
    @Transactional
    @Modifying
    @Query("delete from QuotaWaiter w where w.heartbeatAt < :aliveBefore")
    int deleteStale(@Param("aliveBefore") Instant aliveBefore);
}
