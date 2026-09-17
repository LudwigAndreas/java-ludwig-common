package ru.ludwigandreas.notification.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.notification.repository.entity.QDistributedLockEntity;

/** QueryDSL implementation of {@link DistributedLockQueryRepository}. */
@RequiredArgsConstructor
class DistributedLockQueryRepositoryImpl implements DistributedLockQueryRepository {

    private static final QDistributedLockEntity LOCK = QDistributedLockEntity.distributedLockEntity;

    private final JPAQueryFactory queryFactory;

    @Override
    public long renew(String name, String owner, Instant expiresAt) {
        return queryFactory.update(LOCK)
                .set(LOCK.expiresAt, expiresAt)
                .where(LOCK.id.eq(name).and(LOCK.owner.eq(owner)))
                .execute();
    }

    @Override
    public long release(String name, String owner) {
        // Deleted rather than expired in place: a released lock leaves no row, so the next acquirer
        // takes the insert branch of the upsert and the table stays the size of the locks in use.
        return queryFactory.delete(LOCK)
                .where(LOCK.id.eq(name).and(LOCK.owner.eq(owner)))
                .execute();
    }
}
