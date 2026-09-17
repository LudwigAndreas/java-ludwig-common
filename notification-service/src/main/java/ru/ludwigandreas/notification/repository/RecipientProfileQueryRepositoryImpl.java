package ru.ludwigandreas.notification.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.notification.repository.entity.QRecipientProfileEntity;
import ru.ludwigandreas.notification.repository.entity.RecipientProfileEntity;

/** QueryDSL implementation of {@link RecipientProfileQueryRepository}. */
@RequiredArgsConstructor
class RecipientProfileQueryRepositoryImpl implements RecipientProfileQueryRepository {

    private static final QRecipientProfileEntity PROFILE = QRecipientProfileEntity.recipientProfileEntity;

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<RecipientProfileEntity> lookupByUserId(String userId) {
        return Optional.ofNullable(queryFactory.selectFrom(PROFILE)
                .where(PROFILE.userId.eq(userId))
                .fetchFirst());
    }
}
