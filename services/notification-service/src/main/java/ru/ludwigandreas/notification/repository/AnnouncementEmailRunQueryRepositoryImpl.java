package ru.ludwigandreas.notification.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.identity.entity.QSecurityUserEntity;
import ru.ludwigandreas.identity.entity.UserStatus;
import ru.ludwigandreas.notification.repository.entity.AnnouncementEmailRunEntity;
import ru.ludwigandreas.notification.repository.entity.QAnnouncementEmailRunEntity;

/**
 * QueryDSL-JPA implementation of {@link AnnouncementEmailRunQueryRepository}.
 *
 * <h2>Reading the projection's tables, and the boundary on it</h2>
 *
 * <p>The audience pages come from {@code security_user} and its role collection, which belong to
 * {@code identity-projection-spring-boot-starter}. That is reading entities this service already has
 * on its classpath and already queries for recipient resolution, not a new coupling - and it is
 * <b>read-only</b>. Nothing here writes to them; the projection is fed by its own Kafka consumer and a
 * second writer would be a second source of truth.
 *
 * <p>No native SQL. The whole audience walk is QueryDSL against the generated Q-types, which is worth
 * stating because a keyset page over a joined collection is the shape that usually reaches for it.
 *
 * <h2>Keyset paging, not offset</h2>
 *
 * <p>Every audience query takes a cursor and orders by id. An {@code OFFSET} would get slower with
 * each batch, and - the reason that actually matters - it would skip or repeat rows whenever the
 * underlying set shifted. Over a run lasting minutes it will shift: people are created and
 * deactivated while it is going. A keyset page cannot skip anybody who was there when it started.
 */
@RequiredArgsConstructor
class AnnouncementEmailRunQueryRepositoryImpl implements AnnouncementEmailRunQueryRepository {

    private static final QAnnouncementEmailRunEntity RUN =
            QAnnouncementEmailRunEntity.announcementEmailRunEntity;
    private static final QSecurityUserEntity USER = QSecurityUserEntity.securityUserEntity;

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<AnnouncementEmailRunEntity> findForAnnouncement(UUID announcementId) {
        return Optional.ofNullable(queryFactory.selectFrom(RUN)
                .where(RUN.announcementId.eq(announcementId))
                .fetchFirst());
    }

    @Override
    public List<AnnouncementEmailRunEntity> findClaimable(int limit) {
        // Oldest first, so a long run cannot be starved by newer ones; bounded, so one scheduler
        // cycle cannot hold the lock for an unbounded time.
        return queryFactory.selectFrom(RUN)
                .where(RUN.finishedAt.isNull())
                .orderBy(RUN.submittedAt.asc(), RUN.id.asc())
                .limit(limit)
                .fetch();
    }

    @Override
    public List<String> activeSubjectsAfter(String cursorSubject, int limit) {
        return queryFactory.select(USER.id)
                .from(USER)
                .where(active().and(after(cursorSubject)))
                .orderBy(USER.id.asc())
                .limit(limit)
                .fetch();
    }

    @Override
    public List<String> activeSubjectsWithRoleAfter(String roleCode, String cursorSubject,
                                                    int limit) {
        // The role code as the DIRECTORY names it - bare, not ROLE_-prefixed. security_user_role
        // stores it that way, and the announcement stores the prefixed form for comparison against a
        // principal's roles, so the caller strips it before getting here. The two vocabularies meet
        // in exactly two places and this is one of them.
        return queryFactory.select(USER.id)
                .from(USER)
                .where(active()
                        .and(after(cursorSubject))
                        .and(USER.roles.contains(roleCode)))
                .orderBy(USER.id.asc())
                .limit(limit)
                .fetch();
    }

    /**
     * Deactivated accounts are excluded from a broadcast audience.
     *
     * <p>The same rule recipient resolution already applies per delivery, applied here so that a
     * leaver is never even paged: continuing to write to a leaver's address is both a privacy problem
     * and, for a shared mailbox that has been reassigned, a disclosure to whoever now reads it.
     */
    private static BooleanExpression active() {
        return USER.status.eq(UserStatus.ACTIVE);
    }

    /**
     * The keyset term. A null cursor means "from the beginning", which is a run's first batch rather
     * than a special case worth its own method.
     */
    private static BooleanExpression after(String cursorSubject) {
        return cursorSubject == null ? USER.id.isNotNull() : USER.id.gt(cursorSubject);
    }
}
