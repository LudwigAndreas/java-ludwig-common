package ru.ludwigandreas.example.catalog.service.report;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Ops;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.example.catalog.repository.entity.QCategoryEntity;
import ru.ludwigandreas.example.catalog.repository.entity.QProductEntity;
import ru.ludwigandreas.export.api.RowSource;
import ru.ludwigandreas.export.api.SortDirection;
import ru.ludwigandreas.export.api.SortKey;
import ru.ludwigandreas.export.api.SourceContext;

/**
 * The catalogue extract's base query: keyset-paginated, projected, and scoped by construction.
 *
 * <h2>Keyset, not OFFSET</h2>
 *
 * <p>Page 5,000 of an {@code OFFSET} scan costs the database five thousand pages of work it then
 * discards, so walking a million rows a thousand at a time is quadratic - the last page of the report
 * is a thousand times more expensive than the first. Worse, {@code OFFSET} is not stable: a product
 * inserted or deleted while the report runs shifts every subsequent page, which silently duplicates or
 * skips rows. Keyset pagination is linear and stable because it asks a different question - "the next
 * thousand rows after this exact position" - and the position is a value in the row rather than a count
 * of rows somebody else can change.
 *
 * <p>The position has to be unique for that to hold, which is why {@code id} is appended to whatever
 * ordering the requester asked for. Without it two products with the same name sit at the same position
 * and the page boundary between them is arbitrary.
 *
 * <h2>Why the predicate is conjoined and never inspected</h2>
 *
 * <p>{@link SourceContext#predicate()} arrives already carrying two things: the requester's parsed
 * {@code $filter} and their resolved data scope. This class ANDs it in and does not look at it. There is
 * deliberately no code path here that produces rows without it - that is the difference between a scope
 * check and a scope check somebody can forget.
 *
 * <h2>Projection, not entities</h2>
 *
 * <p>The query selects a {@link ProductReportRow} constructor, so no managed entity is created and the
 * category join is explicit. Streaming entities instead would fill the persistence context with a
 * million objects and take a lazy-loading query per row for the category - both of which present as the
 * export engine exceeding a memory budget it never spent.
 */
@RequiredArgsConstructor
public class ProductRowSource implements RowSource<ProductReportParameters, ProductReportRow> {

    private static final QProductEntity PRODUCT = QProductEntity.productEntity;
    private static final QCategoryEntity CATEGORY = QCategoryEntity.categoryEntity;

    /**
     * The orderable axes, by the column id the report declares.
     *
     * <p>Each entry pairs the path the query orders by with the accessor that reads the same value back
     * out of a fetched row, because keyset pagination needs both: one to order and filter by, one to
     * read the cursor from the last row of the previous page. Declaring them together is what stops them
     * from drifting - an axis added to the map is usable or it does not compile.
     *
     * <p>{@code status} is absent on purpose, matching the {@code sortable = false} on the entity's own
     * {@code @Filterable}: it is a string column holding an enum, so ordering by it is alphabetical
     * rather than by lifecycle, and a report ordered "by status" would be ordered by a coincidence of
     * spelling.
     */
    private static final Map<String, Axis> AXES = axes();

    private final JPAQueryFactory queries;

    private static Map<String, Axis> axes() {
        Map<String, Axis> axes = new LinkedHashMap<>();
        axes.put(CatalogReports.COLUMN_SKU, new Axis(PRODUCT.sku, ProductReportRow::sku));
        axes.put(CatalogReports.COLUMN_NAME, new Axis(PRODUCT.name, ProductReportRow::name));
        axes.put(CatalogReports.COLUMN_PRICE, new Axis(PRODUCT.price, ProductReportRow::price));
        axes.put(CatalogReports.COLUMN_STOCK,
                new Axis(PRODUCT.stockQuantity, ProductReportRow::stockQuantity));
        axes.put(CatalogReports.COLUMN_UPDATED_AT, new Axis(PRODUCT.updatedAt, ProductReportRow::updatedAt));
        return Map.copyOf(axes);
    }

    @Override
    public Set<String> sortableColumns() {
        return AXES.keySet();
    }

    /**
     * Opens a lazily paged stream over the products the requester may see.
     *
     * <p>The stream is not backed by a database cursor and holds no connection between pages, which is
     * the property that makes a report lasting minutes safe: a held cursor would pin a connection and a
     * transaction for the whole run, and the run outliving the transaction timeout is how a long report
     * takes the connection pool with it.
     *
     * @param context the run's parameters, predicate, order and page size
     * @return a stream that fetches one page at a time and stops when a short page arrives
     */
    @Override
    public Stream<ProductReportRow> open(SourceContext<ProductReportParameters> context) {
        List<Axis> ordering = orderingFor(context.sort());
        Iterator<ProductReportRow> pages = new PageIterator(context, ordering);
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(pages, Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }

    /**
     * The axes to order by, in order, with the id tie-break appended.
     *
     * <p>An unknown column id throws rather than being dropped. The engine has already validated the
     * request against {@link #sortableColumns()}, so reaching this is a programming error in the
     * definition or the module, and a silently ignored sort key would make the report's order depend on
     * which of the two.
     */
    private List<Axis> orderingFor(List<SortKey> sort) {
        List<Axis> ordering = new ArrayList<>();
        for (SortKey key : sort) {
            Axis axis = AXES.get(key.columnId());
            if (axis == null) {
                throw new IllegalStateException("Catalogue report cannot order by column '"
                        + key.columnId() + "'; sortable columns are " + AXES.keySet());
            }
            ordering.add(axis.withDirection(key.direction()));
        }
        ordering.add(new Axis(PRODUCT.id, ProductReportRow::id));
        return List.copyOf(ordering);
    }

    /**
     * How many products fall in the window, which the module uses only to choose sync or async.
     *
     * <p>A real {@code COUNT} is affordable here and only here: it runs once per report against the
     * same indexed predicate the walk uses, whereas the thing it is often replaced by - an unbounded
     * scan to find out how big the scan is - doubles the cost of the report. The module treats it as a
     * hint regardless; the row cap and the wall-clock budget are what actually protect the service.
     */
    @Override
    public long estimateRows(SourceContext<ProductReportParameters> context) {
        Long total = baseQuery(context).select(PRODUCT.count()).fetchOne();
        return total == null ? 0L : total;
    }

    /**
     * Everything common to the count and to every page: the joins, the window, the statuses, the scope.
     */
    private JPAQuery<?> baseQuery(SourceContext<ProductReportParameters> context) {
        ProductReportParameters parameters = context.parameters();
        BooleanBuilder where = new BooleanBuilder()
                .and(PRODUCT.updatedAt.goe(parameters.changedFrom()))
                .and(PRODUCT.updatedAt.lt(parameters.changedUntil()));
        if (!parameters.statuses().isEmpty()) {
            where.and(PRODUCT.status.in(parameters.statuses()));
        }
        // The requester's filter and their data scope, as one predicate this class does not unpack.
        context.predicate().ifPresent(where::and);
        return queries.from(PRODUCT).join(PRODUCT.category, CATEGORY).where(where);
    }

    /**
     * One page, positioned after the cursor read from the last row of the previous page.
     *
     * @param context the run
     * @param ordering the axes, tie-break included
     * @param cursor   the last row of the previous page, or null for the first page
     * @return the rows, at most {@code pageSize} of them
     */
    private List<ProductReportRow> page(SourceContext<ProductReportParameters> context,
                                        List<Axis> ordering, ProductReportRow cursor) {
        JPAQuery<ProductReportRow> query = baseQuery(context)
                // The ten-argument constructor, which leaves the enriched field null. Selecting a
                // NULL literal for it instead would push a provider-specific untyped-null problem into
                // the query for no gain.
                .select(Projections.constructor(ProductReportRow.class,
                        PRODUCT.id, PRODUCT.sku, PRODUCT.name, CATEGORY.code, PRODUCT.status,
                        PRODUCT.price, PRODUCT.supplierCost, PRODUCT.stockQuantity, PRODUCT.updatedAt,
                        PRODUCT.supplierPartnerId))
                .orderBy(ordering.stream().map(Axis::orderSpecifier).toArray(OrderSpecifier[]::new))
                .limit(context.pageSize());
        if (cursor != null) {
            query.where(after(ordering, cursor));
        }
        return query.fetch();
    }

    /**
     * The lexicographic "strictly after this row" predicate.
     *
     * <p>Built as a disjunction of one clause per axis: equal on every earlier axis and strictly past
     * on this one. For {@code (name, id)} that is
     * {@code name > :name OR (name = :name AND id > :id)}, which is the standard construction and the
     * only one that is correct for a compound order - comparing the axes independently would skip every
     * row whose name is equal and whose id is larger.
     *
     * <p>{@code Ops.GT} rather than a typed {@code gt} because the axes are heterogeneous - a string, a
     * decimal, an int, an instant, a UUID - and a typed comparison would need the source to know each
     * one's type at a point where all it has is a path and a value read from the same row.
     */
    private static Predicate after(List<Axis> ordering, ProductReportRow cursor) {
        BooleanBuilder disjunction = new BooleanBuilder();
        for (int i = 0; i < ordering.size(); i++) {
            BooleanBuilder clause = new BooleanBuilder();
            for (int earlier = 0; earlier < i; earlier++) {
                clause.and(ordering.get(earlier).equalTo(cursor));
            }
            clause.and(ordering.get(i).strictlyAfter(cursor));
            disjunction.or(clause);
        }
        return disjunction;
    }

    /**
     * One orderable axis: the path to order and compare by, and how to read the same value from a row.
     *
     * @param path       the query path
     * @param accessor   reads the value back out of a fetched row, for the keyset cursor
     * @param descending whether this axis runs backwards, which flips both the ORDER BY and the
     *                   comparison the cursor is built from
     */
    private record Axis(ComparableExpressionBase<?> path,
                        Function<ProductReportRow, Object> accessor,
                        boolean descending) {

        Axis(ComparableExpressionBase<?> path, Function<ProductReportRow, Object> accessor) {
            this(path, accessor, false);
        }

        Axis withDirection(SortDirection direction) {
            return new Axis(path, accessor, direction == SortDirection.DESC);
        }

        @SuppressWarnings("unchecked")
        OrderSpecifier<?> orderSpecifier() {
            return new OrderSpecifier<>(descending ? Order.DESC : Order.ASC,
                    (Expression<Comparable<?>>) (Expression<?>) path);
        }

        Predicate equalTo(ProductReportRow cursor) {
            return Expressions.predicate(Ops.EQ, path, Expressions.constant(accessor.apply(cursor)));
        }

        /** Strictly past the cursor along this axis, which for a descending axis means less than it. */
        Predicate strictlyAfter(ProductReportRow cursor) {
            return Expressions.predicate(descending ? Ops.LT : Ops.GT, path,
                    Expressions.constant(accessor.apply(cursor)));
        }
    }

    /**
     * Walks the pages, fetching the next only when the current one is exhausted.
     *
     * <p>A short page ends the walk. A full last page costs one extra empty query, which is the price of
     * not knowing the total - and a great deal cheaper than the {@code COUNT} that would avoid it.
     */
    private final class PageIterator implements Iterator<ProductReportRow> {

        private final SourceContext<ProductReportParameters> context;
        private final List<Axis> ordering;
        private Iterator<ProductReportRow> current = List.<ProductReportRow>of().iterator();
        private ProductReportRow cursor;
        private boolean exhausted;

        private PageIterator(SourceContext<ProductReportParameters> context, List<Axis> ordering) {
            this.context = context;
            this.ordering = ordering;
        }

        @Override
        public boolean hasNext() {
            if (current.hasNext()) {
                return true;
            }
            if (exhausted) {
                return false;
            }
            List<ProductReportRow> next = page(context, ordering, cursor);
            if (next.size() < context.pageSize()) {
                exhausted = true;
            }
            if (next.isEmpty()) {
                return false;
            }
            cursor = next.get(next.size() - 1);
            current = next.iterator();
            return true;
        }

        @Override
        public ProductReportRow next() {
            if (!hasNext()) {
                throw new NoSuchElementException("The catalogue report's row stream is exhausted");
            }
            return current.next();
        }
    }
}
