package ru.ludwigandreas.notification.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.notification.repository.entity.IdempotencyRecordEntity;
import ru.ludwigandreas.notification.repository.entity.QIdempotencyRecordEntity;

/** QueryDSL implementation of {@link IdempotencyQueryRepository}. */
@RequiredArgsConstructor
class IdempotencyQueryRepositoryImpl implements IdempotencyQueryRepository {

    private static final QIdempotencyRecordEntity RECORD = QIdempotencyRecordEntity.idempotencyRecordEntity;

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<IdempotencyRecordEntity> findActive(String scope, String key, Instant now) {
        return Optional.ofNullable(queryFactory.selectFrom(RECORD)
                .where(RECORD.scope.eq(scope)
                        .and(RECORD.idempotencyKey.eq(key))
                        .and(RECORD.expiresAt.gt(now)))
                .fetchFirst());
    }

    @Override
    public long purgeExpired(Instant now) {
        return queryFactory.delete(RECORD).where(RECORD.expiresAt.lt(now)).execute();
    }
}
