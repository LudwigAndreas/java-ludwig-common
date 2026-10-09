package ru.ludwigandreas.notification.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.notification.repository.entity.AnnouncementEntity;
import ru.ludwigandreas.notification.repository.entity.AudienceKind;
import ru.ludwigandreas.notification.repository.entity.QAnnouncementContentEntity;
import ru.ludwigandreas.notification.repository.entity.QAnnouncementEntity;
import ru.ludwigandreas.notification.repository.entity.QAnnouncementMarkerEntity;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.execution.ODataPage;
import ru.ludwigandreas.odatafilter.execution.ODataQueryExecutor;
import ru.ludwigandreas.odatafilter.execution.ODataSearch;

/**
 * QueryDSL-JPA implementation of {@link AnnouncementQueryRepository}, picked up by Spring Data
 * through the {@code <FragmentInterface>Impl} naming convention.
 *
 * <p>Every statement goes through {@link JPAQueryFactory} and the generated Q-types. <b>This change
 * claims no native-SQL carve-out</b>, and the visibility query is the reason that is worth stating:
 * the obvious design - one feed merging the inbox and announcements - needs a {@code UNION}, which
 * JPQL does not have, and would therefore have required a third fenced package. Keeping the feeds
 * separate is what keeps this file ordinary QueryDSL.
 *
 * <h2>The visibility predicate, and why it joins nothing</h2>
 *
 * <p>{@link #visibleTo} is a disjunction over audience kinds matched against a short in-memory list
 * of the caller's own roles. There is <b>no join to {@code security_user_role}</b>: the caller's
 * roles are already resolved onto their principal, so asking the database who holds a role would be
 * a second answer to a question already answered - and a slower one, on the endpoint a client polls.
 *
 * <p>The role table does matter, but for the email snapshot rather than for visibility. The two are
 * deliberately different: visibility is live, so a revoked role stops granting it on the next read.
 */
@RequiredArgsConstructor
class AnnouncementQueryRepositoryImpl implements AnnouncementQueryRepository {

    private static final QAnnouncementEntity ANNOUNCEMENT = QAnnouncementEntity.announcementEntity;
    private static final QAnnouncementMarkerEntity MARKER =
            QAnnouncementMarkerEntity.announcementMarkerEntity;
    private static final QAnnouncementContentEntity CONTENT =
            QAnnouncementContentEntity.announcementContentEntity;

    private final JPAQueryFactory queryFactory;
    private final ODataQueryExecutor odataExecutor;

    @Override
    public ODataPage<AnnouncementEntity> searchVisible(String ownerUserId, Collection<String> roles,
                                                       Instant now, ODataQueryOptions options) {
        return odataExecutor.search(AnnouncementEntity.class, options,
                ODataSearch.of(ANNOUNCEMENT)
                        .and(visibleTo(roles, now))
                        .and(notDismissedBy(ownerUserId)));
    }

    @Override
    public Optional<AnnouncementEntity> findVisible(String ownerUserId, Collection<String> roles,
                                                    UUID announcementId, Instant now) {
        // Dismissed announcements are included here although the list excludes them: dismissing is
        // not deleting, and a client holding a link to something it has just dismissed must still be
        // able to open it. ownerUserId is therefore unused in the predicate and kept in the
        // signature so that no caller can forget which person they are asking on behalf of.
        return Optional.ofNullable(queryFactory.selectFrom(ANNOUNCEMENT)
                .where(ANNOUNCEMENT.id.eq(announcementId).and(visibleTo(roles, now)))
                .fetchFirst());
    }

    @Override
    public long countVisibleUndismissed(String ownerUserId, Collection<String> roles, Instant now) {
        Long count = queryFactory.select(ANNOUNCEMENT.count())
                .from(ANNOUNCEMENT)
                .where(visibleTo(roles, now).and(notDismissedBy(ownerUserId)))
                .fetchOne();
        return count == null ? 0L : count;
    }

    @Override
    public Optional<AnnouncementEntity> findForAdministration(UUID announcementId) {
        return Optional.ofNullable(queryFactory.selectFrom(ANNOUNCEMENT)
                .where(ANNOUNCEMENT.id.eq(announcementId))
                .fetchFirst());
    }

    @Override
    public long purgeExpiredBefore(Instant visibleUntilBefore, int batchSize) {
        List<UUID> expired = queryFactory.select(ANNOUNCEMENT.id)
                .from(ANNOUNCEMENT)
                .where(ANNOUNCEMENT.visibleUntil.lt(visibleUntilBefore))
                .limit(batchSize)
                .fetch();
        if (expired.isEmpty()) {
            return 0L;
        }
        // Children first and explicitly, although the database would cascade anyway. Deleting them
        // here keeps the counts separable and means the statement does not depend on a foreign key's
        // delete action to be correct - and the cost is a constant number of statements regardless
        // of how large the audience was, which is the property the whole aggregate exists for.
        queryFactory.delete(MARKER).where(MARKER.id.announcementId.in(expired)).execute();
        queryFactory.delete(CONTENT).where(CONTENT.id.announcementId.in(expired)).execute();
        return queryFactory.delete(ANNOUNCEMENT).where(ANNOUNCEMENT.id.in(expired)).execute();
    }

    /**
     * Inside its window, and addressed to somebody holding one of these roles.
     *
     * <p>Written as a disjunction over the audience kinds rather than as two special cases, so that
     * an organisation-unit audience is one more {@code or} rather than a rewrite. The role term is an
     * {@code IN} over the caller's own roles - a short list already in memory - which is why nothing
     * is joined.
     *
     * <p>The role term compares the stored {@code audience_value} against the caller's roles
     * <b>directly</b>, because both are in the {@code ROLE_}-prefixed form: a principal's roles are
     * normalised at the edge of the service, and {@code AnnouncementAdminService} normalises the
     * audience through the same platform function before storing it. That symmetry is load-bearing
     * and its absence is silent - comparing a bare {@code ADMIN} against a prefixed
     * {@code ROLE_ADMIN} matches nothing, so a role-targeted announcement would be visible to
     * <em>nobody</em>, with a perfectly successful publish and no error anywhere.
     *
     * <p>A caller holding no roles still sees {@code EVERYONE} announcements, which is why the role
     * branch is guarded rather than omitted: {@code IN ()} is not valid SQL, and QueryDSL would
     * either drop the term or produce a predicate matching everything, depending on the dialect.
     */
    private static BooleanExpression visibleTo(Collection<String> roles, Instant now) {
        BooleanExpression inWindow = ANNOUNCEMENT.visibleFrom.loe(now)
                .and(ANNOUNCEMENT.visibleUntil.gt(now));

        BooleanExpression everyone = ANNOUNCEMENT.audienceKind.eq(AudienceKind.EVERYONE);
        if (roles == null || roles.isEmpty()) {
            return inWindow.and(everyone);
        }
        BooleanExpression byRole = ANNOUNCEMENT.audienceKind.eq(AudienceKind.ROLE)
                .and(ANNOUNCEMENT.audienceValue.in(roles));
        return inWindow.and(everyone.or(byRole));
    }

    /**
     * {@code NOT EXISTS} rather than a left join, because the absence of a marker is the normal state
     * and most announcements have none at all - a join would materialise rows for a table that is
     * usually empty for the announcement being asked about.
     */
    private static BooleanExpression notDismissedBy(String ownerUserId) {
        return JPAExpressions.selectOne()
                .from(MARKER)
                .where(MARKER.id.announcementId.eq(ANNOUNCEMENT.id)
                        .and(MARKER.id.ownerUserId.eq(ownerUserId)))
                .notExists();
    }
}
