package ru.ludwigandreas.notification.repository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.notification.repository.entity.IdempotencyRecordEntity;

/** The consumer-side dedup table; its queries live in {@link IdempotencyQueryRepository}. */
public interface IdempotencyRecordRepository
        extends BaseRepository<IdempotencyRecordEntity, UUID>, IdempotencyQueryRepository {

    /**
     * Claims {@code (scope, key)} for {@code requestId}, or reports who already holds it.
     *
     * <h2>Why this one is native</h2>
     *
     * <p>The whole value of this statement is that it is <em>one</em> statement. Read-then-insert is
     * the obvious implementation and it is exactly wrong for the case this exists to handle: two
     * replicas processing the same at-least-once Kafka record concurrently both read "no row", both
     * insert, and one takes a constraint violation that aborts a transaction which has by then
     * already written a request and its deliveries. {@code ON CONFLICT ... DO UPDATE} makes the
     * second caller block on the first's row lock, then read the committed winner and return it -
     * no exception, no rollback, no double send.
     *
     * <p>{@code DO NOTHING} would not do: it returns no row on conflict, so the loser would have to
     * re-select, and in {@code READ COMMITTED} a re-select can still miss a row whose inserting
     * transaction has not committed. The no-op {@code DO UPDATE} below exists purely to make the
     * statement return the existing row.
     *
     * <p>The self-assignment deliberately does not touch {@code expires_at}. Extending the window on
     * every duplicate would mean a key that is retried forever never expires, and the table would
     * grow without bound on exactly the traffic pattern it is meant to absorb.
     *
     * <p>Not {@code @Modifying}: that would discard the {@code RETURNING} result set.
     *
     * @return the request id that owns the key - the caller's own on a fresh claim, somebody else's
     *         on a duplicate
     */
    @Query(value = """
            INSERT INTO notification_idempotency (id, scope, idempotency_key, request_id, created_at, expires_at)
            VALUES (:id, :scope, :key, :requestId, :now, :expiresAt)
            ON CONFLICT (scope, idempotency_key)
            DO UPDATE SET scope = notification_idempotency.scope
            RETURNING request_id
            """, nativeQuery = true)
    UUID claim(@Param("id") UUID id,
               @Param("scope") String scope,
               @Param("key") String key,
               @Param("requestId") UUID requestId,
               @Param("now") Instant now,
               @Param("expiresAt") Instant expiresAt);
}
