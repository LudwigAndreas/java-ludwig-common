package ru.ludwigandreas.notification.repository;

import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.QRateLimitWindowEntity;

/** QueryDSL implementation of {@link RateLimitQueryRepository}. */
@RequiredArgsConstructor
class RateLimitQueryRepositoryImpl implements RateLimitQueryRepository {

    private static final QRateLimitWindowEntity WINDOW = QRateLimitWindowEntity.rateLimitWindowEntity;

    private final JPAQueryFactory queryFactory;

    @Override
    public long releasePermits(ChannelKind channel, Instant windowStart, int permits) {
        if (permits <= 0) {
            return 0L;
        }
        return queryFactory.update(WINDOW)
                // GREATEST guards the arithmetic rather than the caller: a return that would drive
                // the counter below zero can only come from a double release, and a negative counter
                // would hand out permits the limit never authorized.
                .set(WINDOW.permitsUsed, Expressions.numberTemplate(Integer.class,
                        "greatest({0} - {1}, 0)", WINDOW.permitsUsed, permits))
                .where(WINDOW.channel.eq(channel).and(WINDOW.windowStart.eq(windowStart)))
                .execute();
    }

    @Override
    public long purgeWindowsBefore(Instant cutoff) {
        return queryFactory.delete(WINDOW).where(WINDOW.windowStart.lt(cutoff)).execute();
    }
}
