package ru.ludwigandreas.notification.repository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.notification.repository.entity.RateLimitWindowEntity;

/** The cluster-wide throughput counter; its non-conditional writes live in the QueryDSL fragment. */
public interface RateLimitWindowRepository
        extends BaseRepository<RateLimitWindowEntity, UUID>, RateLimitQueryRepository {

    /**
     * Creates this channel's counter row for this window if it does not exist yet.
     *
     * <h2>Why native</h2>
     *
     * <p>{@code ON CONFLICT DO NOTHING} is the only way to say "make sure this exists" without either
     * taking an exception on the loser of a race or serializing every poller behind a read. Called
     * only when {@link #reserve} reports no row, so in the steady state it runs once per window per
     * channel rather than once per poll cycle.
     */
    @Modifying
    @Query(value = """
            INSERT INTO notification_rate_limit_window (id, channel, window_start, permits_used)
            VALUES (:id, :channel, :windowStart, 0)
            ON CONFLICT (channel, window_start) DO NOTHING
            """, nativeQuery = true)
    int ensureWindow(@Param("id") UUID id,
                     @Param("channel") String channel,
                     @Param("windowStart") Instant windowStart);

    /**
     * Consumes up to {@code requested} permits and reports how many were actually granted.
     *
     * <h2>Why native</h2>
     *
     * <p>Two things here are beyond JPQL. The inner {@code FOR UPDATE} serializes concurrent
     * reservations on the counter row, which is what makes the limit hold across replicas rather
     * than across threads in one JVM. And the {@code RETURNING} expression is computed from the
     * <em>pre-update</em> value carried out of the subquery, which is the only way to learn the
     * granted amount in one round trip - a plain {@code UPDATE ... RETURNING permits_used} gives the
     * new total, and the new total alone cannot tell a full window from an empty one.
     *
     * <p>Partial grants are deliberate. Refusing the whole batch when only part of it fits would idle
     * the poller for the rest of the window every time a batch straddles the boundary.
     *
     * <p>Not {@code @Modifying}: the {@code RETURNING} row is the answer.
     *
     * @return permits granted, {@code 0} when the window is exhausted, or {@code null} when the
     *         window row does not exist yet
     */
    @Query(value = """
            UPDATE notification_rate_limit_window w
            SET permits_used = LEAST(current.permits_used + :requested, :maxPermits)
            FROM (
                SELECT id, permits_used FROM notification_rate_limit_window
                WHERE channel = :channel AND window_start = :windowStart
                FOR UPDATE
            ) AS current
            WHERE w.id = current.id
            RETURNING LEAST(current.permits_used + :requested, :maxPermits) - current.permits_used
            """, nativeQuery = true)
    Integer reserve(@Param("channel") String channel,
                    @Param("windowStart") Instant windowStart,
                    @Param("requested") int requested,
                    @Param("maxPermits") int maxPermits);
}
