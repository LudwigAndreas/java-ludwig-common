package ru.ludwigandreas.usersettings.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.usersettings.entity.QUserConsentEntity;
import ru.ludwigandreas.usersettings.entity.UserConsentEntity;

@RequiredArgsConstructor
class UserConsentQueryRepositoryImpl implements UserConsentQueryRepository {

    private static final QUserConsentEntity CONSENT = QUserConsentEntity.userConsentEntity;

    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;

    /**
     * Ordered by {@code occurredAt} and then by {@code importedAt}, not by {@code occurredAt} alone.
     * Two decisions can share an instant - a revoke-and-regrant submitted together, or a producer
     * with second-granularity timestamps - and an unstable order there would make "the latest
     * decision" depend on what the database felt like returning.
     */
    @Override
    public List<UserConsentEntity> history(String tenantId, String subject, String consentKey, Instant asOf) {
        BooleanBuilder where = new BooleanBuilder()
                .and(CONSENT.tenantId.eq(tenantId))
                .and(CONSENT.subject.eq(subject));
        if (consentKey != null) {
            where.and(CONSENT.consentKey.eq(consentKey));
        }
        if (asOf != null) {
            where.and(CONSENT.occurredAt.loe(asOf));
        }
        return queryFactory.selectFrom(CONSENT)
                .where(where)
                .orderBy(CONSENT.occurredAt.asc(), CONSENT.importedAt.asc())
                .fetch();
    }

    @Override
    public List<UserConsentEntity> findForBackfill(String tenantId, Collection<String> consentKeys,
                                                   UUID afterId, int limit) {
        BooleanBuilder where = new BooleanBuilder();
        if (tenantId != null) {
            where.and(CONSENT.tenantId.eq(tenantId));
        }
        if (consentKeys != null && !consentKeys.isEmpty()) {
            where.and(CONSENT.consentKey.in(consentKeys));
        }
        if (afterId != null) {
            where.and(CONSENT.id.gt(afterId));
        }
        return queryFactory.selectFrom(CONSENT)
                .where(where)
                .orderBy(CONSENT.id.asc())
                .limit(limit)
                .fetch();
    }

    /**
     * Ids first, then delete by id, because JPQL has no {@code LIMIT} on a delete - and because
     * selecting the rows that will go makes the operation auditable before it runs.
     *
     * <p>The append-only trigger on this table guards UPDATE and not DELETE, precisely so that a
     * retention policy can be carried out. That is the one door left open in an otherwise immutable
     * ledger, and it is open on purpose.
     */
    @Override
    public int purgeOlderThan(Instant cutoff, int batchSize) {
        List<UUID> ids = queryFactory.select(CONSENT.id)
                .from(CONSENT)
                .where(CONSENT.occurredAt.lt(cutoff))
                .orderBy(CONSENT.occurredAt.asc())
                .limit(batchSize)
                .fetch();
        if (ids.isEmpty()) {
            return 0;
        }
        long removed = queryFactory.delete(CONSENT).where(CONSENT.id.in(ids)).execute();
        entityManager.clear();
        return (int) removed;
    }
}
