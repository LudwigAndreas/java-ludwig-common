package ru.ludwigandreas.notification.repository;

import java.time.Instant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.notification.repository.entity.DistributedLockEntity;

/** The lease table behind {@code DistributedLock}; renew and release live in the QueryDSL fragment. */
public interface DistributedLockRepository
        extends BaseRepository<DistributedLockEntity, String>, DistributedLockQueryRepository {

    /**
     * Takes the lock if it is free or its lease has lapsed.
     *
     * <h2>Why this one is native</h2>
     *
     * <p>"Insert it if nobody has it, take it over if the holder's lease expired, and do nothing at
     * all otherwise" is a conditional upsert, and the condition has to be evaluated by the database
     * under the row lock or it is not a lock. {@code ON CONFLICT ... DO UPDATE ... WHERE} is the only
     * construct that does that in one statement; a JPQL read followed by a write would let two
     * replicas both observe an expired lease and both take the lock, which is the precise failure the
     * digest job exists to avoid.
     *
     * <p>The returned owner is compared against the caller's own: an empty result means somebody else
     * holds a live lease, and a returned owner that is not ours would mean a bug, not a partial
     * success. Not {@code @Modifying}, so the {@code RETURNING} row survives.
     *
     * @return the owner now recorded, or {@code null} when the lock was already held
     */
    @Query(value = """
            INSERT INTO notification_lock (id, owner, acquired_at, expires_at)
            VALUES (:name, :owner, :now, :expiresAt)
            ON CONFLICT (id) DO UPDATE
            SET owner = EXCLUDED.owner, acquired_at = EXCLUDED.acquired_at, expires_at = EXCLUDED.expires_at
            WHERE notification_lock.expires_at <= :now
            RETURNING owner
            """, nativeQuery = true)
    String tryAcquire(@Param("name") String name,
                      @Param("owner") String owner,
                      @Param("now") Instant now,
                      @Param("expiresAt") Instant expiresAt);
}
