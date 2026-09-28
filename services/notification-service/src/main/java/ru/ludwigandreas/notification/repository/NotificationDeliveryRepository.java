package ru.ludwigandreas.notification.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;

/**
 * The delivery queue's repository: db-core's {@code BaseRepository} for the ordinary CRUD,
 * {@link DeliveryQueryRepository} for every QueryDSL query this service runs, and the one native
 * statement below that nothing else can express.
 */
public interface NotificationDeliveryRepository
        extends BaseRepository<NotificationDeliveryEntity, UUID>, DeliveryQueryRepository {

    /**
     * Atomically leases up to {@code batchSize} due deliveries on one channel, flipping them to
     * {@code CLAIMED}.
     *
     * <h2>Why this one is native</h2>
     *
     * <p>Neither JPQL nor QueryDSL-JPA can express {@code FOR UPDATE SKIP LOCKED} inside a subquery,
     * and neither can express {@code RETURNING}. Writing it as a QueryDSL select followed by a
     * separate update would be two round trips <em>and</em> a race: between the two statements
     * another replica reads the same rows, and the lock that made the selection safe has already
     * been released. So this is the exception the repository conventions allow for, and it is the
     * only read/claim statement in the service that is not compile-time checked - which is also why
     * it is covered directly by the concurrent-claim integration test.
     *
     * <h2>Why per channel</h2>
     *
     * <p>Claiming across all channels together would let a stalled SMTP server's backlog consume
     * every batch and starve the webhook channel behind it, and it would make a per-provider rate
     * limit impossible to honour - the poller has to know how many permits it holds for <em>this</em>
     * provider before it decides how many rows to take.
     *
     * <h2>Why {@code priority DESC} works</h2>
     *
     * <p>{@code priority} is stored as an integer weight (see {@code DeliveryPriorityConverter}), so
     * this orders HIGH before NORMAL before BULK. Stored as the enum name it would order
     * alphabetically and put BULK first, silently.
     *
     * <p>{@code version} is incremented here so the optimistic lock reflects the claim: a thread
     * that read the row before the lease and then tries to write it fails rather than overwriting a
     * delivery another replica now owns. {@code RETURNING *} hydrates the entities with the post-claim
     * values, version included.
     *
     * <p>Must NOT carry {@code @Modifying} - that forces {@code executeUpdate()} semantics and
     * discards the {@code RETURNING} result set.
     *
     * @param minPriorityWeight lower bound for the lane being claimed; the poller runs the reserved
     *                          high-priority pass with this raised so a bulk backlog cannot consume
     *                          the whole batch
     */
    @Query(value = """
            UPDATE notification_delivery
            SET status = 'CLAIMED', claimed_at = :now, claimed_by = :owner, version = version + 1
            WHERE id IN (
                SELECT d.id FROM notification_delivery d
                WHERE d.status IN ('PENDING', 'FAILED')
                  AND d.channel = :channel
                  AND d.priority >= :minPriorityWeight
                  AND d.next_attempt_at <= :now
                ORDER BY d.priority DESC, d.next_attempt_at, d.id
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """, nativeQuery = true)
    List<NotificationDeliveryEntity> claimBatch(@Param("channel") String channel,
                                               @Param("minPriorityWeight") int minPriorityWeight,
                                               @Param("batchSize") int batchSize,
                                               @Param("now") Instant now,
                                               @Param("owner") String owner);
}
