package ru.ludwigandreas.idempotency.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.querydsl.jpa.impl.JPAUpdateClause;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ru.ludwigandreas.idempotency.entity.ClaimState;
import ru.ludwigandreas.idempotency.entity.IdempotencyClaimEntity;
import ru.ludwigandreas.idempotency.entity.QIdempotencyClaimEntity;

/**
 * QueryDSL implementation of {@link IdempotencyClaimQueryRepository}.
 */
class IdempotencyClaimQueryRepositoryImpl implements IdempotencyClaimQueryRepository {

    private static final QIdempotencyClaimEntity CLAIM = QIdempotencyClaimEntity.idempotencyClaimEntity;

    private final JPAQueryFactory queryFactory;

    /**
     * Builds its own {@link JPAQueryFactory} over the injected entity manager rather than taking one
     * from the context.
     *
     * <p>Not a style choice, and the reason is recorded in {@code audit-spring-boot-starter} too: a
     * {@code JPAQueryFactory} bean guarded by {@code @ConditionalOnMissingBean} is not safe in a
     * library. Two autoconfigurations with no ordering between them each see no factory and each
     * register one, and anything injecting {@code JPAQueryFactory} by type then fails to start on an
     * ambiguity neither module caused alone. {@code user-settings-spring-boot-starter} publishes one,
     * so a third module contributing another would be exactly that setup.
     *
     * <p>The injected {@code EntityManager} is Spring Data's shared, transaction-aware proxy, so one
     * factory per repository singleton is correct: it delegates to whatever persistence context the
     * calling thread is in, which here is a request thread, a Kafka listener thread and the purge's
     * scheduler thread at the same time.
     *
     * @param entityManager the shared entity manager
     */
    IdempotencyClaimQueryRepositoryImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public Optional<IdempotencyClaimEntity> findActive(String scope, String key, Instant now) {
        return Optional.ofNullable(queryFactory.selectFrom(CLAIM)
                .where(identifies(scope, key).and(CLAIM.expiresAt.gt(now)))
                .fetchFirst());
    }

    @Override
    public Optional<IdempotencyClaimEntity> findAny(String scope, String key) {
        return Optional.ofNullable(queryFactory.selectFrom(CLAIM)
                .where(identifies(scope, key))
                .fetchFirst());
    }

    /**
     * Conditional on the caller still holding the claim <em>and</em> the claim still being in progress.
     *
     * <p>Both halves fence. Matching the request id alone would let a holder whose lease expired - and
     * whose claim was then taken over and completed by somebody else, and then expired and taken over
     * again by this same id, which a retrying caller reusing its request id makes possible - overwrite a
     * newer holder's answer. Requiring {@code IN_PROGRESS} also makes a completion arriving twice a
     * no-op rather than a second write of the same bytes.
     */
    @Override
    // SUPPRESS CHECKSTYLE ParameterNumber - see the interface.
    public boolean complete(String scope, String key, UUID requestId, Integer status, String contentType,
                            String headers, byte[] body) {
        JPAUpdateClause update = queryFactory.update(CLAIM)
                .set(CLAIM.state, ClaimState.COMPLETED)
                .setNull(CLAIM.leaseExpiresAt);
        if (status == null) {
            // Completed with nothing to replay, which is legitimate: a consumer has no response, and an
            // endpoint may be configured not to store one. The columns are nulled rather than left as
            // they are, so a reclaimed row cannot keep a previous holder's body.
            update.setNull(CLAIM.responseStatus)
                    .setNull(CLAIM.responseContentType)
                    .setNull(CLAIM.responseHeaders)
                    .setNull(CLAIM.responseBody);
        } else {
            update.set(CLAIM.responseStatus, status)
                    .set(CLAIM.responseContentType, contentType)
                    .set(CLAIM.responseHeaders, headers)
                    .set(CLAIM.responseBody, body);
        }
        return update.where(heldBy(scope, key, requestId).and(CLAIM.state.eq(ClaimState.IN_PROGRESS)))
                .execute() == 1;
    }

    @Override
    public boolean fail(String scope, String key, UUID requestId, String reason) {
        return queryFactory.update(CLAIM)
                .set(CLAIM.state, ClaimState.FAILED)
                .setNull(CLAIM.leaseExpiresAt)
                .set(CLAIM.failureReason, reason)
                .where(heldBy(scope, key, requestId).and(CLAIM.state.eq(ClaimState.IN_PROGRESS)))
                .execute() == 1;
    }

    /**
     * Renewal also requires the current lease not to have lapsed.
     *
     * <p>Without that, a holder that paused long enough for its lease to expire could renew as though
     * nothing had happened - even though another instance may already have taken the claim over and be
     * repeating the work. This is the {@code run_id} fencing {@code JdbcRunLock} gained for the same
     * reason, expressed against the lease instead of an acquisition id, because a claim's identity is
     * already its holder's request id.
     */
    @Override
    public boolean renewLease(String scope, String key, UUID requestId, Instant leaseUntil, Instant now) {
        return queryFactory.update(CLAIM)
                .set(CLAIM.leaseExpiresAt, leaseUntil)
                .where(heldBy(scope, key, requestId)
                        .and(CLAIM.state.eq(ClaimState.IN_PROGRESS))
                        .and(CLAIM.leaseExpiresAt.gt(now)))
                .execute() == 1;
    }

    /**
     * Deletes expired claims in bounded batches.
     *
     * <p>Bounded rather than "delete everything expired", because the first run after a TTL is shortened
     * would otherwise be one statement over the whole table: a long transaction that pins the oldest
     * transaction id, stops autovacuum on a table receiving one insert per request, and holds locks
     * across the busiest path in the service. The caller loops, renewing its lease between batches.
     *
     * <p>Two statements rather than one {@code DELETE ... LIMIT}, which Postgres does not support: the
     * ids are selected first, then deleted by id. The window between them is harmless - a claim that
     * expired stays expired, and a row that a concurrent reclaim has just taken over no longer matches
     * the {@code expires_at} predicate the ids were chosen by, so it survives.
     */
    @Override
    public long purgeExpired(Instant now, int batchSize) {
        List<UUID> expired = queryFactory.select(CLAIM.id)
                .from(CLAIM)
                .where(CLAIM.expiresAt.lt(now))
                .limit(batchSize)
                .fetch();
        if (expired.isEmpty()) {
            return 0L;
        }
        return queryFactory.delete(CLAIM)
                .where(CLAIM.id.in(expired).and(CLAIM.expiresAt.lt(now)))
                .execute();
    }

    @Override
    public long countExpired(Instant now) {
        Long count = queryFactory.select(CLAIM.count())
                .from(CLAIM)
                .where(CLAIM.expiresAt.lt(now))
                .fetchOne();
        return count == null ? 0L : count;
    }

    private static BooleanExpression identifies(String scope, String key) {
        return CLAIM.scope.eq(scope).and(CLAIM.idempotencyKey.eq(key));
    }

    private static BooleanExpression heldBy(String scope, String key, UUID requestId) {
        return identifies(scope, key).and(CLAIM.requestId.eq(requestId));
    }
}
