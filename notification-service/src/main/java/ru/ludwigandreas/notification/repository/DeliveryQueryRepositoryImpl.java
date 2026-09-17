package ru.ludwigandreas.notification.repository;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatus;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatusHistoryEntity;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.repository.entity.QDeliveryContentEntity;
import ru.ludwigandreas.notification.repository.entity.QDeliveryStatusHistoryEntity;
import ru.ludwigandreas.notification.repository.entity.QNotificationDeliveryEntity;
import ru.ludwigandreas.notification.repository.query.DeliverySearchCriteria;
import ru.ludwigandreas.notification.repository.query.QueueDepth;
import ru.ludwigandreas.odatafilter.core.ODataFilterService;
import ru.ludwigandreas.odatafilter.core.ODataQuery;
import ru.ludwigandreas.security.data.DataAccessGuard;
import ru.ludwigandreas.security.data.DataAction;

/**
 * QueryDSL-JPA implementation of {@link DeliveryQueryRepository}, picked up by Spring Data through
 * the {@code <FragmentInterface>Impl} naming convention.
 *
 * <p>Every statement here goes through {@link JPAQueryFactory} and the generated Q-types: no JDBC,
 * no JPQL string, no method-name-derived query. The one dynamic piece is the client's OData
 * {@code $filter}, and that is not free-form either - {@link ODataFilterService} only produces paths
 * the entity's own {@code @Filterable} annotations allow, which is what keeps the recipient address
 * and the rendered body unreachable from a query string.
 */
@RequiredArgsConstructor
class DeliveryQueryRepositoryImpl implements DeliveryQueryRepository {

    /** The name this resource's data-scope mapping and policies are registered under. */
    private static final String RESOURCE_TYPE = "notification-delivery";

    private static final QNotificationDeliveryEntity DELIVERY =
            QNotificationDeliveryEntity.notificationDeliveryEntity;
    private static final QDeliveryStatusHistoryEntity HISTORY =
            QDeliveryStatusHistoryEntity.deliveryStatusHistoryEntity;
    private static final QDeliveryContentEntity CONTENT =
            QDeliveryContentEntity.deliveryContentEntity;

    /**
     * Same root and alias as {@link #DELIVERY}, reached dynamically. Needed only to turn the
     * {@code $orderby} clause - property paths resolved at runtime by definition - into
     * {@code OrderSpecifier}s over the very same query root.
     */
    private static final PathBuilder<NotificationDeliveryEntity> ROOT =
            new PathBuilder<>(NotificationDeliveryEntity.class, DELIVERY.getMetadata().getName());

    /** Stable tie-breaker so paging cannot return the same row on two pages. */
    private static final OrderSpecifier<?>[] DEFAULT_ORDER = {DELIVERY.createdAt.desc(), DELIVERY.id.asc()};

    /** The two states the claim query considers, and therefore what "waiting" means for the gauges. */
    private static final List<DeliveryStatus> CLAIMABLE =
            List.of(DeliveryStatus.PENDING, DeliveryStatus.FAILED);

    /** What a scrubbed delivery's variable map becomes - valid JSON, and visibly empty. */
    private static final String EMPTY_VARIABLES = "{}";

    /** States a delivery never leaves, and therefore the only ones retention may remove. */
    private static final List<DeliveryStatus> SETTLED = List.of(
            DeliveryStatus.SENT, DeliveryStatus.DELIVERED, DeliveryStatus.DEAD,
            DeliveryStatus.SUPPRESSED, DeliveryStatus.CANCELLED, DeliveryStatus.COLLAPSED);

    private final JPAQueryFactory queryFactory;
    private final ODataFilterService filterService;
    private final DataAccessGuard dataAccessGuard;

    /**
     * The caller's data scope is ANDed into the query itself, next to the client's own
     * {@code $filter}.
     *
     * <p>That placement is the point. Filtering the page after fetching it would return fewer than
     * {@code $top} rows and a total that counts rows the caller may not see, so both the page and the
     * pager would be wrong - and the database would still have read and shipped those rows. In the
     * {@code WHERE} clause, paging, counting and the index all stay correct, and a caller entitled to
     * nothing gets an empty page rather than a 403 that would confirm matching deliveries exist.
     */
    @Override
    public Page<NotificationDeliveryEntity> search(DeliverySearchCriteria criteria) {
        ODataQuery<NotificationDeliveryEntity> query = filterService.parse(
                NotificationDeliveryEntity.class, criteria.filter(), criteria.top(), criteria.skip(),
                criteria.orderBy(), false);
        Pageable pageable = query.pageable();
        Predicate scoped = dataAccessGuard.predicate(RESOURCE_TYPE, DataAction.READ).and(query.predicate());

        List<NotificationDeliveryEntity> content = queryFactory.selectFrom(DELIVERY)
                .where(scoped)
                .orderBy(orderSpecifiers(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(content, pageable, () -> count(scoped));
    }

    @Override
    public List<DeliveryStatusHistoryEntity> historyOf(UUID deliveryId) {
        return queryFactory.selectFrom(HISTORY)
                .where(HISTORY.deliveryId.eq(deliveryId))
                .orderBy(HISTORY.occurredAt.asc(), HISTORY.id.asc())
                .fetch();
    }

    @Override
    public List<NotificationDeliveryEntity> findByRequestId(UUID requestId) {
        return queryFactory.selectFrom(DELIVERY)
                .where(DELIVERY.requestId.eq(requestId))
                .orderBy(DELIVERY.createdAt.asc(), DELIVERY.id.asc())
                .fetch();
    }

    @Override
    public Optional<NotificationDeliveryEntity> findByProviderMessageId(ChannelKind channel,
                                                                        String providerMessageId) {
        return Optional.ofNullable(queryFactory.selectFrom(DELIVERY)
                .where(DELIVERY.channel.eq(channel).and(DELIVERY.providerMessageId.eq(providerMessageId)))
                .fetchFirst());
    }

    @Override
    public Optional<NotificationDeliveryEntity> findByDedupKey(String dedupKey) {
        return Optional.ofNullable(queryFactory.selectFrom(DELIVERY)
                .where(DELIVERY.dedupKey.eq(dedupKey))
                .fetchFirst());
    }

    @Override
    public long reclaimStale(Instant staleBefore) {
        return queryFactory.update(DELIVERY)
                .set(DELIVERY.status, DeliveryStatus.PENDING)
                .setNull(DELIVERY.claimedAt)
                .setNull(DELIVERY.claimedBy)
                .set(DELIVERY.version, DELIVERY.version.add(1))
                .where(DELIVERY.status.eq(DeliveryStatus.CLAIMED).and(DELIVERY.claimedAt.lt(staleBefore)))
                .execute();
    }

    @Override
    public long releaseClaimed(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0L;
        }
        return queryFactory.update(DELIVERY)
                .set(DELIVERY.status, DeliveryStatus.PENDING)
                .setNull(DELIVERY.claimedAt)
                .setNull(DELIVERY.claimedBy)
                .set(DELIVERY.version, DELIVERY.version.add(1))
                // Status is part of the predicate so a delivery this instance already settled in the
                // same cycle is not dragged back to PENDING and sent a second time.
                .where(DELIVERY.id.in(ids).and(DELIVERY.status.eq(DeliveryStatus.CLAIMED)))
                .execute();
    }

    @Override
    public List<QueueDepth> queueDepth() {
        List<Tuple> rows = queryFactory
                .select(DELIVERY.channel, DELIVERY.priority, DELIVERY.count())
                .from(DELIVERY)
                .where(DELIVERY.status.in(CLAIMABLE))
                .groupBy(DELIVERY.channel, DELIVERY.priority)
                .fetch();
        return rows.stream()
                .map(row -> new QueueDepth(
                        row.get(DELIVERY.channel),
                        row.get(DELIVERY.priority),
                        Optional.ofNullable(row.get(DELIVERY.count())).orElse(0L)))
                .toList();
    }

    @Override
    public Optional<Instant> oldestClaimableCreatedAt() {
        return Optional.ofNullable(queryFactory.select(DELIVERY.createdAt.min())
                .from(DELIVERY)
                .where(DELIVERY.status.in(CLAIMABLE))
                .fetchOne());
    }

    @Override
    public List<String> dueDigestGroups(Instant now, int limit) {
        return queryFactory.select(DELIVERY.digestGroup)
                .from(DELIVERY)
                .where(batchedAndDue(now))
                .groupBy(DELIVERY.digestGroup)
                // Oldest window first, so a backlog drains in the order recipients would expect.
                .orderBy(DELIVERY.nextAttemptAt.min().asc())
                .limit(limit)
                .fetch();
    }

    @Override
    public List<NotificationDeliveryEntity> dueBatchedIn(String digestGroup, Instant now) {
        return queryFactory.selectFrom(DELIVERY)
                .where(batchedAndDue(now).and(DELIVERY.digestGroup.eq(digestGroup)))
                .orderBy(DELIVERY.createdAt.asc(), DELIVERY.id.asc())
                .fetch();
    }

    @Override
    public long scrubRecipientDataBefore(Instant cutoff) {
        // Nulled rather than overwritten with a marker: a marker is a value that looks like an
        // address and is not one, and every reader would have to know the magic string. The
        // predicate excludes rows already scrubbed so a daily purge does not rewrite the whole
        // history table every time it runs.
        return queryFactory.update(DELIVERY)
                .setNull(DELIVERY.recipientAddress)
                .setNull(DELIVERY.recipientUserId)
                // The variable map is scrubbed with the address: it carries display names and
                // order references, which are exactly as personal as the address is.
                .set(DELIVERY.variables, EMPTY_VARIABLES)
                .set(DELIVERY.version, DELIVERY.version.add(1))
                .where(DELIVERY.status.in(SETTLED)
                        .and(DELIVERY.settledAt.lt(cutoff))
                        .and(DELIVERY.recipientAddress.isNotNull()))
                .execute();
    }

    @Override
    public long purgeSettledBefore(Instant cutoff) {
        return queryFactory.delete(DELIVERY)
                .where(DELIVERY.status.in(SETTLED).and(DELIVERY.settledAt.lt(cutoff)))
                .execute();
    }

    @Override
    public long purgeHistoryBefore(Instant cutoff) {
        return queryFactory.delete(HISTORY)
                .where(HISTORY.occurredAt.lt(cutoff))
                .execute();
    }

    @Override
    public long purgeContentDue(Instant now) {
        return queryFactory.delete(CONTENT)
                .where(CONTENT.purgeAfter.lt(now))
                .execute();
    }

    private BooleanExpression batchedAndDue(Instant now) {
        return DELIVERY.status.eq(DeliveryStatus.BATCHED)
                .and(DELIVERY.digestGroup.isNotNull())
                .and(DELIVERY.nextAttemptAt.loe(now));
    }

    private long count(Predicate predicate) {
        Long total = queryFactory.select(DELIVERY.count()).from(DELIVERY).where(predicate).fetchOne();
        return total == null ? 0L : total;
    }

    private OrderSpecifier<?>[] orderSpecifiers(Sort sort) {
        if (sort.isUnsorted()) {
            return DEFAULT_ORDER;
        }
        return sort.stream()
                .map(order -> orderSpecifier(order.getProperty(), order.isAscending()))
                .toArray(OrderSpecifier<?>[]::new);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private OrderSpecifier<?> orderSpecifier(String property, boolean ascending) {
        String[] segments = property.split("\\.");
        PathBuilder<?> parent = ROOT;
        for (int i = 0; i < segments.length - 1; i++) {
            parent = parent.get(segments[i]);
        }
        ComparableExpressionBase path = parent.getComparable(segments[segments.length - 1], Comparable.class);
        return new OrderSpecifier(ascending ? Order.ASC : Order.DESC, path);
    }
}
