package ru.ludwigandreas.odatafilter.execution;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Predicate;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import org.springframework.data.domain.Pageable;
import ru.ludwigandreas.odatafilter.core.ODataFilterService;
import ru.ludwigandreas.odatafilter.core.ODataQuery;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.querydsl.ODataPaths;

/**
 * Runs a caller's OData query options against the database and returns the page.
 *
 * <h2>What this exists to delete</h2>
 *
 * <p>{@link ODataFilterService} stops at a {@link Predicate} and a {@link Pageable}, and a repository
 * then has four mechanical steps left: turn the {@code Sort} into QueryDSL ordering expressions, apply
 * the offset and limit, run the count, and assemble the page. Those four were written out by hand in
 * every repository that used this module, identically - including a reflective property-path walk and
 * its {@code @SuppressWarnings({"rawtypes","unchecked"})}, duplicated verbatim across two services. A
 * step that every consumer performs the same way is this module's job, not theirs.
 *
 * <p>A repository that needs more than the default still uses this: {@link ODataSearch} carries the
 * extra predicates and the query shaping, so a fetch join or a projection does not force a consumer
 * back to writing the four steps. An execution path a consumer cannot shape is one that gets bypassed,
 * and bypassing it brings the hand-written code straight back.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * ODataPage<ProductEntity> page = executor.search(ProductEntity.class, options,
 *         ODataSearch.of(PRODUCT)
 *                 .and(dataAccessGuard.predicate(RESOURCE_TYPE, DataAction.READ))
 *                 .content(query -> query.leftJoin(PRODUCT.category).fetchJoin()));
 * }</pre>
 *
 * <p>{@code PRODUCT} is the generated default instance, {@code QProductEntity.productEntity}. Its
 * alias is checked against the one the predicate is built with, because a mismatch cross-joins rather
 * than failing - see {@link ODataPaths#requireMatchingAlias}.
 *
 * <h2>Why its own package rather than {@code querydsl}</h2>
 *
 * <p>Because {@code core.ODataFilterService} already depends on {@code querydsl.PredicateBuilder}, and
 * this class depends on {@code ODataFilterService} - so living in {@code querydsl} made
 * {@code core <-> querydsl} a package cycle. {@code execution} depends on both and neither depends back,
 * which is the shape the dependency actually has: parsing is one layer, path building is another, and
 * running a query against a database needs both.
 *
 * <h2>Transactions</h2>
 *
 * <p>This declares none and starts none. It runs inside whatever transaction the calling service
 * layer opened, which is where this platform puts transaction boundaries. Two statements - the page
 * and the count - are issued, so a caller that needs them consistent needs that transaction.
 */
public class ODataQueryExecutor {

    private final JPAQueryFactory queryFactory;
    private final ODataFilterService filterService;

    public ODataQueryExecutor(JPAQueryFactory queryFactory, ODataFilterService filterService) {
        this.queryFactory = queryFactory;
        this.filterService = filterService;
    }

    /** Parses the options and runs the query, with no extra predicates and no query shaping. */
    public <T> ODataPage<T> search(Class<T> entityType, ODataQueryOptions options, ODataSearch<T> search) {
        return execute(filterService.parse(entityType, options), entityType, search);
    }

    /**
     * Runs an already-parsed query, for a caller that needed the parse result first - to inspect the
     * resolved {@code Sort}, or to reject a request before touching the database.
     */
    public <T> ODataPage<T> execute(ODataQuery<T> query, Class<T> entityType, ODataSearch<T> search) {
        ODataPaths.requireMatchingAlias(entityType, search.root());

        Pageable pageable = query.pageable();
        Predicate where = combine(query, search);

        JPAQuery<T> contentQuery = search.applyContent(queryFactory)
                .where(where)
                .orderBy(orderSpecifiers(entityType, pageable))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize());
        List<T> content = contentQuery.fetch();

        Long total = query.countRequested() ? count(search, where) : null;
        return new ODataPage<>(content, pageable.getOffset(), pageable.getPageSize(), total);
    }

    /**
     * The caller's {@code $filter} and the repository's own predicates, as one condition.
     *
     * <p>A {@link BooleanBuilder} rather than a chain of {@code and} calls because the filter may be
     * absent and so may the extra predicates, and an empty builder contributes nothing to the
     * {@code WHERE} clause instead of contributing a literal {@code true}.
     */
    private <T> Predicate combine(ODataQuery<T> query, ODataSearch<T> search) {
        BooleanBuilder builder = new BooleanBuilder();
        query.predicate().ifPresent(builder::and);
        search.extraPredicates().forEach(builder::and);
        return builder;
    }

    /**
     * The ordering always comes from the resolved {@code Sort}, which carries the entity's
     * {@code defaultOrderBy} tie-breaker appended after whatever the caller asked for. So there is no
     * unordered case to fall back on, and that matters: a query with no total order lets the database
     * return rows however it likes, which makes {@code $skip=0} and {@code $skip=20} two independent
     * queries that can show the same row twice.
     */
    private OrderSpecifier<?>[] orderSpecifiers(Class<?> entityType, Pageable pageable) {
        return ODataPaths.orderSpecifiers(entityType, pageable.getSort());
    }

    private <T> long count(ODataSearch<T> search, Predicate where) {
        Long total = search.applyCount(queryFactory).where(where).fetchOne();
        return total == null ? 0L : total;
    }
}
