package ru.ludwigandreas.odatafilter.execution;

import java.util.List;
import java.util.function.Function;

/**
 * One page of results, as the database actually produced it.
 *
 * <h2>Why this is not a Spring Data {@code Page}</h2>
 *
 * <p>A {@code Page} always carries a total, so it cannot represent a result whose caller sent
 * {@code $count=false} - which is the whole point of that option. {@code Slice} can, but then a
 * repository returns one of two types depending on a query parameter, and every caller branches.
 * This carries the total as "present or absent" and lets one return type cover both.
 *
 * <p>It is deliberately <em>not</em> a wire type: it holds entities. A controller maps it and builds
 * the response envelope, which is {@code web-core}'s {@code PageResponse} and stays the platform's one
 * paged envelope - this type never reaches a client and is not a second one.
 *
 * @param content       the page's rows, in the resolved order
 * @param offset        the absolute row offset applied, i.e. the caller's {@code $skip}
 * @param size          the page size applied, which may be smaller than the one requested
 * @param totalElements the total matching rows, or {@code null} if the caller declined the count
 */
public record ODataPage<T>(List<T> content, long offset, int size, Long totalElements) {

    public ODataPage {
        content = content == null ? List.of() : List.copyOf(content);
    }

    /** Whether the caller was given a total, i.e. did not send {@code $count=false}. */
    public boolean hasTotal() {
        return totalElements != null;
    }

    /**
     * Maps the rows, keeping the paging members - the call a service makes on its way out of the
     * persistence layer, so that no entity travels further.
     */
    public <R> ODataPage<R> map(Function<? super T, ? extends R> mapper) {
        return new ODataPage<>(content.stream().<R>map(mapper::apply).toList(), offset, size, totalElements);
    }
}
