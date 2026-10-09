package ru.ludwigandreas.odatafilter.core;

import com.querydsl.core.types.Predicate;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

/**
 * Result of parsing a caller's OData query options for entity {@code T}: a QueryDSL
 * {@link Predicate} if the caller supplied a filter, a {@link Pageable} covering
 * {@code $top}/{@code $skip}/{@code $orderby} plus the entity's configured default ordering, and
 * whether the caller wants a total.
 *
 * <p>{@code T} is a phantom type parameter - it carries no runtime state - so that a repository
 * holding an {@code ODataQuery<ProductEntity>} cannot accidentally run it against another root.
 *
 * <h2>Why {@link #predicate()} is an {@link Optional} rather than an always-true predicate</h2>
 *
 * <p>It used to be {@code Expressions.TRUE} when the caller sent no {@code $filter}, which is
 * indistinguishable from a caller whose filter happens to match everything. A consumer that only wants
 * the predicate - a report over a whole result set, a saved filter replayed by a batch job - then had
 * no way to tell "nothing to apply" from "apply this", and ANDed {@code true} into every statement
 * forever. An absent {@code Optional} says it once, in the type.
 */
public final class ODataQuery<T> {

    private final Predicate predicate;
    private final Pageable pageable;
    private final String rawFilter;
    private final boolean countRequested;

    public ODataQuery(Predicate predicate, Pageable pageable, String rawFilter, boolean countRequested) {
        this.predicate = predicate;
        this.pageable = pageable;
        this.rawFilter = rawFilter;
        this.countRequested = countRequested;
    }

    /** The translated {@code $filter}, or empty if the caller supplied none. */
    public Optional<Predicate> predicate() {
        return Optional.ofNullable(predicate);
    }

    public Pageable pageable() {
        return pageable;
    }

    /** The raw {@code $filter} string, or {@code null} if the caller did not supply one. */
    public String rawFilter() {
        return rawFilter;
    }

    /**
     * Whether the caller wants a total, i.e. did not send {@code $count=false}. On a deep-paged
     * filtered query the count is usually the slowest part of the request, so this is the one option
     * whose answer changes how many statements run.
     *
     * @return {@code false} only for an explicit {@code $count=false}
     */
    public boolean countRequested() {
        return countRequested;
    }

    @Override
    public String toString() {
        // Deliberately without the predicate: it renders the caller's literal values, and this string
        // reaches logs. The paths and operators a filter named are available from the parse itself.
        return "ODataQuery[hasPredicate=" + (predicate != null)
                + ", pageable=" + pageable + ", countRequested=" + countRequested + "]";
    }
}
