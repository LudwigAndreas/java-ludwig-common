package ru.ludwigandreas.outbox.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.outbox.entity.OutboxMessage;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Requires {@code @EnableJpaRepositories(repositoryBaseClass =
 * ru.ludwigandreas.db.core.repository.BaseRepositoryImpl.class)} on the consuming application (same requirement {@code
 * db-core} already imposes on every {@link BaseRepository} subinterface), scanning {@code
 * ru.ludwigandreas.outbox.repository} alongside the application's own repository packages.
 */
public interface OutboxMessageRepository extends BaseRepository<OutboxMessage, UUID>, OutboxMessageRepositoryCustom {

    /**
     * Crash recovery: a poller instance that claimed rows (flipped them to {@code PROCESSING}) and
     * died before recording an outcome would otherwise strand them forever. Resets any row still
     * {@code PROCESSING} with {@code locked_at} older than {@code staleBefore} back to {@code PENDING}.
     * <p>
     * {@code @Transactional} is required and is not redundant. Spring Data's repository proxy applies
     * {@code SimpleJpaRepository}'s own transaction attributes to the CRUD methods it implements, but a
     * {@code @Modifying} query declared on this interface gets none - so without this the call fails
     * with {@code TransactionRequiredException}, and since the only caller is
     * {@link ru.ludwigandreas.outbox.scheduler.OutboxStaleReclaimScheduler}, which catches and logs,
     * the effect is that stale PROCESSING rows are never reclaimed and the failure is visible only as
     * a recurring stack trace in the log.
     */
    @Transactional
    @Modifying
    @Query("update OutboxMessage m set m.status = ru.ludwigandreas.outbox.entity.OutboxStatus.PENDING, "
            + "m.lockedAt = null, m.lockedBy = null "
            + "where m.status = ru.ludwigandreas.outbox.entity.OutboxStatus.PROCESSING and m.lockedAt < :staleBefore")
    int reclaimStale(@Param("staleBefore") Instant staleBefore);

    Optional<OutboxMessage> findByIdempotencyKey(String idempotencyKey);
}
