package ru.ludwigandreas.reconciliation.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.reconciliation.entity.QuotaLease;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Occupied slots of the partner-scoped quotas. */
public interface QuotaLeaseRepository extends BaseRepository<QuotaLease, UUID> {

    /**
     * How many slots of a quota are currently held.
     *
     * <p>Counted by expiry, never by row count: a lease whose holder died is still a row, and
     * counting it would shrink the quota by one per crash until the integration stopped entirely.
     *
     * @param quotaName the quota
     * @param now       the current instant
     * @return live slots
     */
    long countByQuotaNameAndExpiresAtGreaterThan(String quotaName, Instant now);

    /**
     * Live leases of a quota, for the actuator's view and for the reclaim sweep's "is this one still
     * doing anything" check.
     *
     * @param quotaName the quota
     * @param now       the current instant
     * @return the leases
     */
    List<QuotaLease> findByQuotaNameAndExpiresAtGreaterThan(String quotaName, Instant now);

    /**
     * Leases whose holder has stopped heartbeating, for the reclaim sweep.
     *
     * @param now      the current instant
     * @param pageable the page, so one sweep cannot load an unbounded number
     * @return the expired leases
     */
    List<QuotaLease> findByExpiresAtLessThanOrderByExpiresAt(Instant now, Pageable pageable);

    /**
     * The lease held for a particular remote job.
     *
     * @param jobId the job
     * @return the lease, if one is held
     */
    Optional<QuotaLease> findByJobId(UUID jobId);

    /**
     * Extends a lease, but only if it is still held by this holder.
     *
     * <p>Conditional on the current owner and on not having already expired, so that a renewal
     * arriving after another instance reclaimed the slot fails rather than silently taking it back -
     * which would put two holders in one slot, both convinced they are the only one.
     *
     * @param id        the lease
     * @param owner     the expected holder
     * @param now       the current instant
     * @param expiresAt the new expiry
     * @return 1 if the lease is still held by {@code owner}, 0 if it has been lost
     */
    @Transactional
    @Modifying
    @Query("update QuotaLease l set l.heartbeatAt = :now, l.expiresAt = :expiresAt "
            + "where l.id = :id and l.ownerInstance = :owner and l.expiresAt > :now")
    int renew(@Param("id") UUID id,
              @Param("owner") String owner,
              @Param("now") Instant now,
              @Param("expiresAt") Instant expiresAt);
}
