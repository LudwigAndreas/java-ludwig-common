package ru.ludwigandreas.usersettings.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.usersettings.entity.QUserSettingAuditEntity;
import ru.ludwigandreas.usersettings.entity.UserSettingAuditEntity;

@RequiredArgsConstructor
class UserSettingAuditQueryRepositoryImpl implements UserSettingAuditQueryRepository {

    private static final QUserSettingAuditEntity AUDIT = QUserSettingAuditEntity.userSettingAuditEntity;

    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;

    @Override
    public List<UserSettingAuditEntity> forSubject(String tenantId, String subject, int limit) {
        return queryFactory.selectFrom(AUDIT)
                .where(AUDIT.tenantId.eq(tenantId).and(AUDIT.subject.eq(subject)))
                .orderBy(AUDIT.occurredAt.desc())
                .limit(limit)
                .fetch();
    }

    /**
     * Two statements - select the ids, then delete by id - rather than one delete with a limit,
     * because JPQL has no {@code LIMIT} on a delete. Selecting the ids first also means the delete
     * touches exactly the rows that were chosen, which a second evaluation of the same predicate
     * would not guarantee on a table still being written to.
     */
    @Override
    public int purgeOlderThan(Instant cutoff, int batchSize) {
        List<UUID> ids = queryFactory.select(AUDIT.id)
                .from(AUDIT)
                .where(AUDIT.occurredAt.lt(cutoff))
                .orderBy(AUDIT.occurredAt.asc())
                .limit(batchSize)
                .fetch();
        if (ids.isEmpty()) {
            return 0;
        }
        long removed = queryFactory.delete(AUDIT).where(AUDIT.id.in(ids)).execute();
        entityManager.clear();
        return (int) removed;
    }
}
