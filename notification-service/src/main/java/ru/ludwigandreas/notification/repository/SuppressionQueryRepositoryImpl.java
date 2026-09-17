package ru.ludwigandreas.notification.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.QSuppressionEntity;
import ru.ludwigandreas.notification.repository.entity.SuppressionEntity;

/** QueryDSL implementation of {@link SuppressionQueryRepository}. */
@RequiredArgsConstructor
class SuppressionQueryRepositoryImpl implements SuppressionQueryRepository {

    private static final QSuppressionEntity SUPPRESSION = QSuppressionEntity.suppressionEntity;

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<SuppressionEntity> findActive(ChannelKind channel, String normalizedAddress, Instant now) {
        return Optional.ofNullable(queryFactory.selectFrom(SUPPRESSION)
                .where(SUPPRESSION.channel.eq(channel)
                        .and(SUPPRESSION.address.eq(normalizedAddress))
                        .and(active(now)))
                .fetchFirst());
    }

    @Override
    public Set<String> findActiveAddresses(ChannelKind channel, Collection<String> normalizedAddresses,
                                           Instant now) {
        if (normalizedAddresses == null || normalizedAddresses.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(queryFactory.select(SUPPRESSION.address)
                .from(SUPPRESSION)
                .where(SUPPRESSION.channel.eq(channel)
                        .and(SUPPRESSION.address.in(normalizedAddresses))
                        .and(active(now)))
                .fetch());
    }

    @Override
    public List<SuppressionEntity> listActive(ChannelKind channel, Instant now, int limit) {
        return queryFactory.selectFrom(SUPPRESSION)
                .where(SUPPRESSION.channel.eq(channel).and(active(now)))
                .orderBy(SUPPRESSION.createdAt.desc(), SUPPRESSION.id.asc())
                .limit(limit)
                .fetch();
    }

    @Override
    public long purgeExpired(Instant now) {
        // expiresAt IS NULL is permanent and is never compacted away: a spam complaint does not
        // stop being true, and re-sending to a complainer is how a sending domain gets blocked.
        return queryFactory.delete(SUPPRESSION)
                .where(SUPPRESSION.expiresAt.isNotNull().and(SUPPRESSION.expiresAt.lt(now)))
                .execute();
    }

    private BooleanExpression active(Instant now) {
        return SUPPRESSION.expiresAt.isNull().or(SUPPRESSION.expiresAt.gt(now));
    }
}
