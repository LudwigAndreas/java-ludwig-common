package ru.ludwigandreas.example.catalog.repository;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;
import ru.ludwigandreas.db.core.util.Predicates;
import ru.ludwigandreas.example.catalog.repository.entity.ProductEntity;
import ru.ludwigandreas.example.catalog.repository.entity.QProductEntity;
import ru.ludwigandreas.example.catalog.repository.query.ProductSearchCriteria;
import ru.ludwigandreas.odatafilter.core.ODataFilterService;
import ru.ludwigandreas.odatafilter.core.ODataQuery;
import ru.ludwigandreas.security.data.DataAccessGuard;
import ru.ludwigandreas.security.data.DataAction;

/**
 * QueryDSL-JPA implementation of {@link ProductQueryRepository}, picked up by Spring Data through
 * the {@code <FragmentInterface>Impl} naming convention.
 *
 * <p>Every query goes through {@link JPAQueryFactory} and the generated {@link QProductEntity}: no
 * JDBC, no JPQL/SQL string, no method-name-derived query. The one dynamic piece is the client's
 * OData {@code $filter}, and that is not free-form either - {@link ODataFilterService} only
 * produces paths that the entity's own {@code @Filterable} annotations allow, rejecting anything
 * else before a query is built.
 */
@RequiredArgsConstructor
class ProductQueryRepositoryImpl implements ProductQueryRepository {

    /** The name this resource's policies and scope mapping are registered under. */
    private static final String RESOURCE_TYPE = "product";

    private static final QProductEntity PRODUCT = QProductEntity.productEntity;

    /**
     * Same root, same alias as {@link #PRODUCT}, reached dynamically - needed only to turn the
     * {@code $orderby} clause (property paths resolved at runtime by definition) into
     * {@code OrderSpecifier}s over the very same query root.
     */
    private static final PathBuilder<ProductEntity> ROOT =
            new PathBuilder<>(ProductEntity.class, PRODUCT.getMetadata().getName());

    /** Stable tie-breaker so paging can't return the same row on two pages. */
    private static final OrderSpecifier<?> DEFAULT_ORDER = PRODUCT.createdAt.desc();

    private final JPAQueryFactory queryFactory;
    private final ODataFilterService filterService;
    private final DataAccessGuard dataAccessGuard;

    /**
     * Deliberately unscoped. This is the uniqueness check behind the SKU constraint, not a read of
     * someone's data: scoping it would let a caller create a product whose SKU collides with one they
     * cannot see, and the insert would then fail on the database constraint with an error nobody can
     * explain. The result never leaves the service - see {@code ProductServiceImpl}.
     */
    @Override
    public Optional<ProductEntity> lookupBySku(String sku) {
        return Optional.ofNullable(queryFactory.selectFrom(PRODUCT)
                .where(PRODUCT.sku.equalsIgnoreCase(sku))
                .fetchFirst());
    }

    @Override
    public boolean skuTaken(String sku, UUID excludedId) {
        return queryFactory.selectOne()
                .from(PRODUCT)
                .where(Predicates.allOf(
                        PRODUCT.sku.equalsIgnoreCase(sku),
                        Predicates.whenNotNull(excludedId, PRODUCT.id::ne)))
                .fetchFirst() != null;
    }

    /**
     * The caller's data scope is ANDed into the query itself, next to the client's own {@code $filter}.
     *
     * <p>That placement is the whole point. Filtering the page after fetching it would return fewer than
     * {@code $top} rows and a total that counts rows the caller may not see, so both the page and the
     * pager would be wrong; and the database would still have read and shipped those rows. In the
     * {@code WHERE} clause, paging, counting and the index all stay correct, and a caller entitled to
     * nothing simply gets an empty page rather than a 403 that would confirm matching products exist.
     */
    @Override
    public Page<ProductEntity> search(ProductSearchCriteria criteria) {
        ODataQuery<ProductEntity> query = filterService.parse(
                ProductEntity.class, criteria.filter(), criteria.top(), criteria.skip(),
                criteria.orderBy(), false);
        Pageable pageable = query.pageable();
        Predicate scoped = dataAccessGuard.predicate(RESOURCE_TYPE, DataAction.READ)
                .and(query.predicate());

        List<ProductEntity> content = queryFactory.selectFrom(PRODUCT)
                // The category is mapped into every response, so fetch it with the page instead of
                // paying one extra SELECT per row.
                .leftJoin(PRODUCT.category).fetchJoin()
                .where(scoped)
                .orderBy(orderSpecifiers(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(content, pageable, () -> count(scoped));
    }

    private long count(Predicate predicate) {
        Long total = queryFactory.select(PRODUCT.count())
                .from(PRODUCT)
                .where(predicate)
                .fetchOne();
        return total == null ? 0L : total;
    }

    private OrderSpecifier<?>[] orderSpecifiers(Sort sort) {
        if (sort.isUnsorted()) {
            return new OrderSpecifier<?>[] {DEFAULT_ORDER};
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
        ComparableExpressionBase path =
                parent.getComparable(segments[segments.length - 1], Comparable.class);
        return new OrderSpecifier(ascending ? Order.ASC : Order.DESC, path);
    }
}
