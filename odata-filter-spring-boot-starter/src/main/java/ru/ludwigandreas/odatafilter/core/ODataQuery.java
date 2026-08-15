package ru.ludwigandreas.odatafilter.core;

import com.querydsl.core.types.Predicate;
import org.springframework.data.domain.Pageable;

/**
 * Result of parsing a caller's OData query options for entity {@code T}: a ready-to-use QueryDSL
 * {@link Predicate} (pass to {@code QuerydslPredicateExecutor.findAll(predicate, pageable)}) and
 * {@link Pageable} (covering {@code $top}/{@code $skip}/{@code $orderby}). {@code T} is a phantom
 * type parameter - it carries no runtime state - used by the Spring MVC argument resolver to know
 * which entity's policy to apply for a controller method parameter declared as
 * {@code ODataQuery<Employee>}.
 */
public final class ODataQuery<T> {

    private final Predicate predicate;
    private final Pageable pageable;
    private final boolean count;
    private final String rawFilter;

    public ODataQuery(Predicate predicate, Pageable pageable, boolean count, String rawFilter) {
        this.predicate = predicate;
        this.pageable = pageable;
        this.count = count;
        this.rawFilter = rawFilter;
    }

    public Predicate predicate() {
        return predicate;
    }

    public Pageable pageable() {
        return pageable;
    }

    /** Echoes the caller's {@code $count} (default {@code true}); use to decide whether to compute a total. */
    public boolean count() {
        return count;
    }

    /** The raw {@code $filter} string, or {@code null} if the caller did not supply one. */
    public String rawFilter() {
        return rawFilter;
    }

    @Override
    public String toString() {
        return "ODataQuery[predicate=" + predicate + ", pageable=" + pageable + ", count=" + count + "]";
    }
}
