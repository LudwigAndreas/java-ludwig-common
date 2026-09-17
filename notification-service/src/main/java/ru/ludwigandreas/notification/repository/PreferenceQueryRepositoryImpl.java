package ru.ludwigandreas.notification.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.db.core.util.Predicates;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.QRecipientPreferenceEntity;
import ru.ludwigandreas.notification.repository.entity.RecipientPreferenceEntity;

/** QueryDSL implementation of {@link PreferenceQueryRepository}. */
@RequiredArgsConstructor
class PreferenceQueryRepositoryImpl implements PreferenceQueryRepository {

    /** The category value meaning "every category the recipient may decline". */
    public static final String WILDCARD_CATEGORY = "*";

    private static final QRecipientPreferenceEntity PREFERENCE =
            QRecipientPreferenceEntity.recipientPreferenceEntity;

    private final JPAQueryFactory queryFactory;

    @Override
    public List<RecipientPreferenceEntity> findApplicable(String userId, String category, ChannelKind channel) {
        BooleanExpression categoryMatches =
                PREFERENCE.category.eq(category).or(PREFERENCE.category.eq(WILDCARD_CATEGORY));
        // A null channel on the row means "every channel", so it is applicable to this one too.
        BooleanExpression channelMatches = PREFERENCE.channel.isNull().or(PREFERENCE.channel.eq(channel));
        return queryFactory.selectFrom(PREFERENCE)
                .where(PREFERENCE.userId.eq(userId).and(categoryMatches).and(channelMatches))
                .fetch();
    }

    @Override
    public List<RecipientPreferenceEntity> findAllForUser(String userId) {
        return queryFactory.selectFrom(PREFERENCE)
                .where(PREFERENCE.userId.eq(userId))
                .orderBy(PREFERENCE.category.asc(), PREFERENCE.id.asc())
                .fetch();
    }

    @Override
    public Optional<RecipientPreferenceEntity> findExact(String userId, String category, ChannelKind channel) {
        return Optional.ofNullable(queryFactory.selectFrom(PREFERENCE)
                .where(Predicates.allOf(
                        PREFERENCE.userId.eq(userId),
                        PREFERENCE.category.eq(category),
                        channel == null ? PREFERENCE.channel.isNull() : PREFERENCE.channel.eq(channel)))
                .fetchFirst());
    }
}
