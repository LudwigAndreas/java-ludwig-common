package ru.ludwigandreas.odatafilter.core;

/**
 * The OData query options a caller sent, as one value.
 *
 * <h2>Why a record and not five parameters</h2>
 *
 * <p>The signature this replaced was
 * {@code parse(Class<T>, String filter, Integer top, Integer skip, String orderBy)} - two strings and
 * two boxed integers, positional. Transposing {@code filter} and {@code orderBy} at a call site
 * compiled and produced a filter parsed as an ordering clause; so did transposing {@code top} and
 * {@code skip}, with no error at all, just the wrong page. Named components cannot be transposed
 * without the compiler noticing.
 *
 * <p>It is also what lets a controller declare the five query parameters once instead of five
 * {@code @RequestParam}s per endpoint, which is the whole of this type's other job. It holds no JPA
 * entity type and no QueryDSL expression, deliberately: those are what made the deprecated
 * {@code ODataQuery<T>} controller parameter unusable - the first names persistence at the REST
 * boundary, the second is a query plan in a layer that cannot execute it. This record is the request,
 * not the query, so it may travel from the controller down through the service to the repository that
 * parses it.
 *
 * <h2>Why every component is nullable</h2>
 *
 * <p>Each one is absent when the caller did not send it, and absent is not the same as a default:
 * {@code top == null} resolves to the entity's {@code defaultPageSize}, which only the policy knows,
 * and {@code count == null} means the caller expressed no opinion and gets a total. Substituting a
 * default here would put policy in a request object and make "the caller asked for 20" and "the caller
 * asked for nothing" indistinguishable.
 *
 * @param filter  the raw {@code $filter} expression, or {@code null}
 * @param orderBy the raw {@code $orderby} clause, or {@code null}
 * @param top     the requested page size, or {@code null} for the entity's default
 * @param skip    the requested absolute row offset, or {@code null} for 0
 * @param count   whether the caller wants a total, or {@code null} if it did not say - see
 *                {@link #countRequested()}
 */
public record ODataQueryOptions(String filter, String orderBy, Integer top, Integer skip, Boolean count) {

    /** No options at all: the first page of everything, with a total. */
    public static ODataQueryOptions none() {
        return new ODataQueryOptions(null, null, null, null, null);
    }

    /**
     * Only a filter, for a caller to whom paging and ordering do not apply.
     *
     * <p>This is the shape a report or a replayed saved filter needs: {@code $top} and {@code $skip}
     * are how a screen asks for a page, and a report is the whole result by definition.
     */
    public static ODataQueryOptions filterOnly(String filter) {
        return new ODataQueryOptions(filter, null, null, null, null);
    }

    /** The four paging and ordering options, with {@code $count} unspecified. */
    public static ODataQueryOptions of(String filter, String orderBy, Integer top, Integer skip) {
        return new ODataQueryOptions(filter, orderBy, top, skip, null);
    }

    /**
     * Whether the caller wants the total computed. True unless it explicitly said otherwise, because
     * a pager is what most callers of a collection endpoint need and silence must not take one away.
     *
     * @return {@code false} only for an explicit {@code $count=false}
     */
    public boolean countRequested() {
        return count == null || count;
    }

    /** Whether the caller supplied a filter expression worth parsing. */
    public boolean hasFilter() {
        return filter != null && !filter.isBlank();
    }
}
