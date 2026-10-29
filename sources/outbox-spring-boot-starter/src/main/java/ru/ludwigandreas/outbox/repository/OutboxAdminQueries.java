package ru.ludwigandreas.outbox.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.entity.OutboxStatus;
import ru.ludwigandreas.outbox.entity.QOutboxMessage;

/**
 * The queries the outbox's administrative endpoints read with: browse by state newest first, count by state, and
 * pick the messages a bulk retry moves.
 *
 * <p>QueryDSL over the generated Q-type, like every repository query on this platform. Kept apart from the
 * dispatcher's own claim queries because nothing on the dispatch path should be able to reach them.
 */
public class OutboxAdminQueries {

    private static final QOutboxMessage MESSAGE = QOutboxMessage.outboxMessage;

    /** Hibernate reads a lock timeout of -2 as SKIP LOCKED. */
    private static final String LOCK_TIMEOUT_HINT = "jakarta.persistence.lock.timeout";
    private static final int SKIP_LOCKED = -2;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * One page of messages matching the filter, newest first, after the given position.
     *
     * @param filter         the caller's parsed {@code $filter}, or null for every message
     * @param afterCreatedAt the last returned row's creation time, or null for the first page
     * @param afterId        the last returned row's id, or null for the first page
     * @param limit          how many rows to read
     * @return the rows, newest first
     */
    public List<OutboxMessage> page(Predicate filter, Instant afterCreatedAt, UUID afterId, int limit) {
        BooleanBuilder where = new BooleanBuilder(filter);
        if (afterCreatedAt != null && afterId != null) {
            where.and(MESSAGE.createdAt.lt(afterCreatedAt)
                    .or(MESSAGE.createdAt.eq(afterCreatedAt).and(MESSAGE.id.lt(afterId))));
        }
        return query().selectFrom(MESSAGE).where(where)
                .orderBy(MESSAGE.createdAt.desc(), MESSAGE.id.desc())
                .limit(limit)
                .fetch();
    }

    /**
     * How many messages there are per state and destination.
     *
     * @return status, destination and count per row
     */
    public List<Tuple> countsByStatusAndDestination() {
        return query().select(MESSAGE.status, MESSAGE.destination, MESSAGE.count())
                .from(MESSAGE)
                .groupBy(MESSAGE.status, MESSAGE.destination)
                .orderBy(MESSAGE.status.asc(), MESSAGE.destination.asc())
                .fetch();
    }

    /**
     * One message, unlocked, for reading.
     *
     * @param id the message id
     * @return the message, or null when there is none
     */
    public OutboxMessage find(UUID id) {
        return query().selectFrom(MESSAGE).where(MESSAGE.id.eq(id)).fetchOne();
    }

    /**
     * One message, locked for update.
     *
     * <p>The dispatcher claims rows with a native {@code FOR UPDATE SKIP LOCKED} that does not go through the
     * entity's version, so an administrative change made by load-then-save could overwrite a claim made in between
     * and discard a message that is already being sent. Locked first, the claim skips the row; a row the claim got
     * first is read back as {@code PROCESSING} and refused.
     *
     * @param id the message id
     * @return the locked message, or null when there is none
     */
    public OutboxMessage lockById(UUID id) {
        return query().selectFrom(MESSAGE).where(MESSAGE.id.eq(id))
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .fetchOne();
    }

    /**
     * The oldest messages a bulk retry would move, locked for update and skipping any the dispatcher holds.
     *
     * @param filter what to match; its status is required by the caller
     * @param limit  at most how many
     * @return the locked messages, oldest first, so a capped retry drains a backlog from its head
     */
    public List<OutboxMessage> lockOldestMatching(Filter filter, int limit) {
        return query().selectFrom(MESSAGE).where(filter.predicate())
                .orderBy(MESSAGE.createdAt.asc(), MESSAGE.id.asc())
                .limit(limit)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setHint(LOCK_TIMEOUT_HINT, SKIP_LOCKED)
                .fetch();
    }

    /**
     * Whether more messages match than were just moved.
     *
     * @param filter what to match
     * @return true when at least one row matches
     */
    public boolean anyMatching(Filter filter) {
        return query().selectOne().from(MESSAGE).where(filter.predicate()).fetchFirst() != null;
    }

    private JPAQueryFactory query() {
        return new JPAQueryFactory(entityManager);
    }

    /**
     * What an administrative listing or bulk retry matches; every member is optional.
     *
     * @param status        the state
     * @param eventType     the event type
     * @param destination   the destination
     * @param createdBefore only messages created before this instant
     */
    public record Filter(OutboxStatus status, String eventType, String destination, Instant createdBefore) {

        BooleanBuilder predicate() {
            BooleanBuilder where = new BooleanBuilder();
            if (status != null) {
                where.and(MESSAGE.status.eq(status));
            }
            if (eventType != null && !eventType.isBlank()) {
                where.and(MESSAGE.eventType.eq(eventType));
            }
            if (destination != null && !destination.isBlank()) {
                where.and(MESSAGE.destination.eq(destination));
            }
            if (createdBefore != null) {
                where.and(MESSAGE.createdAt.lt(createdBefore));
            }
            return where;
        }
    }
}
