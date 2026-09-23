package ru.ludwigandreas.odatafilter.core;

import com.querydsl.core.types.Predicate;
import org.springframework.data.domain.Pageable;

/**
 * Result of parsing a caller's OData query options for entity {@code T}: a ready-to-use QueryDSL
 * {@link Predicate} and a {@link Pageable} covering {@code $top}/{@code $skip}/{@code $orderby}
 * plus the entity's configured default ordering.
 *
 * <p>{@code T} is a phantom type parameter - it carries no runtime state - so that a repository
 * holding an {@code ODataQuery<ProductEntity>} cannot accidentally run it against another root.
 */
public final class ODataQuery<T> {

    private final Predicate predicate;
    private final Pageable pageable;
    private final String rawFilter;

    public ODataQuery(Predicate predicate, Pageable pageable, String rawFilter) {
        this.predicate = predicate;
        this.pageable = pageable;
        this.rawFilter = rawFilter;
    }

    public Predicate predicate() {
        return predicate;
    }

    public Pageable pageable() {
        return pageable;
    }

    /** The raw {@code $filter} string, or {@code null} if the caller did not supply one. */
    public String rawFilter() {
        return rawFilter;
    }

    @Override
    public String toString() {
        return "ODataQuery[predicate=" + predicate + ", pageable=" + pageable + "]";
    }
}
