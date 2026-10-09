package ru.ludwigandreas.odatafilter.execution;

import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.EntityPathBase;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * What a repository tells {@link ODataQueryExecutor} about its query, beyond the caller's options.
 *
 * <p>Everything here is optional. A repository with no extra requirements passes
 * {@code ODataSearch.of(root)} and writes nothing else.
 *
 * <h2>Why the content query and the count query are shaped separately</h2>
 *
 * <p>The first thing a real repository adds is a fetch join - the category is in every product
 * response, so fetching it with the page beats one extra {@code SELECT} per row. QueryDSL refuses a
 * fetch join on a count query, and rightly: a join that multiplies rows changes the count. So a single
 * shaping hook applied to both queries would break the moment anyone used it for its main purpose.
 *
 * <p>The alternative considered was one hook plus the executor stripping fetch joins from the count.
 * That means inspecting and rewriting a consumer's query, and getting it wrong yields a wrong total
 * rather than an error - a failure nobody sees until a pager is off.
 *
 * @param <T> the entity being queried
 */
public final class ODataSearch<T> {

    private final EntityPathBase<T> root;
    private final List<Predicate> extraPredicates = new ArrayList<>();
    private UnaryOperator<JPAQuery<T>> contentQuery = UnaryOperator.identity();
    private UnaryOperator<JPAQuery<Long>> countQuery = UnaryOperator.identity();

    private ODataSearch(EntityPathBase<T> root) {
        this.root = root;
    }

    /**
     * The query root, which must be the entity's generated default instance.
     *
     * <p>{@link ODataQueryExecutor} checks its alias against the one the predicate is built with and
     * refuses a mismatch - see {@link ODataPaths#requireMatchingAlias}.
     */
    public static <T> ODataSearch<T> of(EntityPathBase<T> root) {
        return new ODataSearch<>(root);
    }

    /**
     * ANDs another predicate into both the content and the count query.
     *
     * <p>This is where a caller's data-access scope goes. In the {@code WHERE} clause the paging, the
     * total and the index all stay correct, and a caller entitled to nothing gets an empty page rather
     * than a 403 that would confirm matching rows exist. Filtering the page after fetching it would
     * return fewer than {@code $top} rows against a total counting rows the caller may not see, so both
     * the page and the pager would be wrong - and the database would still have read and shipped them.
     */
    public ODataSearch<T> and(Predicate predicate) {
        if (predicate != null) {
            extraPredicates.add(predicate);
        }
        return this;
    }

    /**
     * Shapes the query that fetches the rows - a fetch join, a projection, a hint.
     *
     * <p>Do not add a {@code where} here; use {@link #and(Predicate)}, so the condition reaches the
     * count query too and the total stays consistent with the page.
     */
    public ODataSearch<T> content(UnaryOperator<JPAQuery<T>> customizer) {
        this.contentQuery = customizer == null ? UnaryOperator.identity() : customizer;
        return this;
    }

    /**
     * Shapes the query that computes the total, for the rare case where it needs a join of its own.
     *
     * <p>Left alone, the count runs against the bare root with the same predicates, which is correct
     * for every query whose content customizer only adds fetch joins.
     */
    public ODataSearch<T> count(UnaryOperator<JPAQuery<Long>> customizer) {
        this.countQuery = customizer == null ? UnaryOperator.identity() : customizer;
        return this;
    }

    EntityPathBase<T> root() {
        return root;
    }

    List<Predicate> extraPredicates() {
        return List.copyOf(extraPredicates);
    }

    JPAQuery<T> applyContent(JPAQueryFactory factory) {
        return contentQuery.apply(factory.selectFrom(root));
    }

    JPAQuery<Long> applyCount(JPAQueryFactory factory) {
        return countQuery.apply(factory.select(root.count()).from(root));
    }
}
