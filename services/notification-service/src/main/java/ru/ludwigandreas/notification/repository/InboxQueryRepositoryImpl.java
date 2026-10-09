package ru.ludwigandreas.notification.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.notification.repository.entity.InboxItemEntity;
import ru.ludwigandreas.notification.repository.entity.QInboxItemContentEntity;
import ru.ludwigandreas.notification.repository.entity.QInboxItemEntity;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.execution.ODataPage;
import ru.ludwigandreas.odatafilter.execution.ODataQueryExecutor;
import ru.ludwigandreas.odatafilter.execution.ODataSearch;

/**
 * QueryDSL-JPA implementation of {@link InboxQueryRepository}, picked up by Spring Data through the
 * {@code <FragmentInterface>Impl} naming convention.
 *
 * <p>Every statement here goes through {@link JPAQueryFactory} and the generated Q-types: no JDBC,
 * no JPQL string, no method-name-derived query. <b>This change claims no native-SQL carve-out</b> -
 * mark-all-read and both purges are QueryDSL update and delete clauses, which is worth stating
 * because a bulk conditional update is exactly the shape that usually reaches for native SQL. The two
 * fenced packages in this repository exist for statements QueryDSL genuinely cannot express
 * ({@code ON CONFLICT}, {@code RETURNING}, {@code COPY}); nothing here is one of those.
 *
 * <p>The caller's {@code $filter} is the only dynamic piece and is not free-form:
 * {@link ODataQueryExecutor} produces only paths the entity's own {@code @Filterable} annotations
 * allow, and {@code InboxItemEntity.ownerUserId} deliberately carries none - so no query string can
 * reach the owner however it is spelled, which is the second of the three defences that keep the
 * inbox from becoming an existence oracle.
 */
@RequiredArgsConstructor
class InboxQueryRepositoryImpl implements InboxQueryRepository {

    private static final QInboxItemEntity ITEM = QInboxItemEntity.inboxItemEntity;
    private static final QInboxItemContentEntity CONTENT =
            QInboxItemContentEntity.inboxItemContentEntity;

    private final JPAQueryFactory queryFactory;
    private final ODataQueryExecutor odataExecutor;

    @Override
    public ODataPage<InboxItemEntity> search(String ownerUserId, ODataQueryOptions options) {
        return odataExecutor.search(InboxItemEntity.class, options,
                ODataSearch.of(ITEM).and(ITEM.ownerUserId.eq(ownerUserId))
                        .and(ITEM.dismissedAt.isNull()));
    }

    @Override
    public Optional<InboxItemEntity> findOwned(String ownerUserId, UUID itemId) {
        // Owner and id together in one predicate, rather than loading by id and comparing the owner
        // afterwards. The two are equivalent only as long as nobody later reads the loaded entity
        // before the comparison, and this way there is nothing to forget.
        //
        // Dismissed items are included here although the list excludes them: dismissing is not
        // deleting, and a client holding a link to an item it has just dismissed must still be able
        // to open it.
        return Optional.ofNullable(queryFactory.selectFrom(ITEM)
                .where(ITEM.ownerUserId.eq(ownerUserId).and(ITEM.id.eq(itemId)))
                .fetchFirst());
    }

    @Override
    public long countUnread(String ownerUserId) {
        Long count = queryFactory.select(ITEM.count())
                .from(ITEM)
                .where(unread(ownerUserId))
                .fetchOne();
        return count == null ? 0L : count;
    }

    @Override
    public long markAllRead(String ownerUserId, Instant readAt) {
        // One statement, not a read-then-write loop. A loop would be N round trips and would also be
        // wrong under concurrency: an item arriving between the read and the write would be counted
        // and not updated.
        //
        // seenAt is coalesced rather than overwritten, because reading something implies having seen
        // it but must not rewrite the instant at which it was actually first seen. The three instants
        // are a record of what happened, not a current state.
        return queryFactory.update(ITEM)
                .set(ITEM.readAt, readAt)
                .set(ITEM.seenAt, ITEM.seenAt.coalesce(readAt))
                .where(unread(ownerUserId))
                .execute();
    }

    @Override
    public long purgeSettledBefore(Instant settledBefore, int batchSize) {
        // Content first, then the item. The database would enforce the order anyway through the
        // ON DELETE CASCADE, but deleting content explicitly keeps the two counts separable and
        // means the statement does not depend on a cascade to be correct.
        List<UUID> expired = queryFactory.select(ITEM.id)
                .from(ITEM)
                .where(ITEM.readAt.coalesce(ITEM.dismissedAt).isNotNull()
                        .and(ITEM.readAt.coalesce(ITEM.dismissedAt).lt(settledBefore)))
                .limit(batchSize)
                .fetch();
        return deleteItems(expired);
    }

    @Override
    public long purgeUnreadBefore(Instant createdBefore, int batchSize) {
        List<UUID> expired = queryFactory.select(ITEM.id)
                .from(ITEM)
                .where(ITEM.readAt.isNull()
                        .and(ITEM.dismissedAt.isNull())
                        .and(ITEM.createdAt.lt(createdBefore)))
                .limit(batchSize)
                .fetch();
        return deleteItems(expired);
    }

    /**
     * Unread means unread <em>and not dismissed</em>.
     *
     * <p>One expression used by both the count and the bulk update, because the badge and the "mark
     * all read" button have to agree about which items they are talking about - and a recipient who
     * dismissed something without opening it has dealt with it, so counting it as outstanding would
     * make the badge impossible to clear.
     */
    private static BooleanExpression unread(String ownerUserId) {
        return ITEM.ownerUserId.eq(ownerUserId)
                .and(ITEM.readAt.isNull())
                .and(ITEM.dismissedAt.isNull());
    }

    private long deleteItems(List<UUID> ids) {
        if (ids.isEmpty()) {
            return 0L;
        }
        queryFactory.delete(CONTENT).where(CONTENT.id.in(ids)).execute();
        return queryFactory.delete(ITEM).where(ITEM.id.in(ids)).execute();
    }
}
