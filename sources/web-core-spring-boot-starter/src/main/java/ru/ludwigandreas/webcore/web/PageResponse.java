package ru.ludwigandreas.webcore.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Envelope for paged results.
 *
 * <p>Spring Data's own {@code Page} is not returned directly. Its JSON shape is an implementation
 * detail of the persistence library - it has changed between versions, and serializing it emits a
 * {@code pageable} object with sort descriptors and offsets that describe how the query was executed
 * rather than what the client asked for. Returning it makes every consumer of the API depend on the
 * service's choice of data-access library, which is the coupling the DTO layer exists to prevent.
 * Spring Boot 3.3 warns about exactly this on startup.
 *
 * <p>Six members, all of them things a client can act on: what came back, where in the result set it
 * starts, how big a page is, and how much there is in total.
 *
 * <h2>Why both {@code offset} and {@code page}</h2>
 *
 * <p>{@code page} is <em>derived</em> from the offset and is lossy: an offset that is not a whole
 * multiple of {@code size} has no integer page, so at {@code size=20} the offsets 20 and 25 both
 * yield {@code page=1}. A client paging by absolute offset - which is what OData's {@code $skip} is,
 * and it is explicitly allowed not to align to {@code $top} - therefore cannot compute its next
 * request from a page number alone. {@code offset} is the authoritative position and {@code page} is
 * the convenience for the ordinary aligned case; {@code page} must never be the only position an
 * envelope reports, which is the defect this member was added to fix.
 *
 * <h2>Why the totals are nullable</h2>
 *
 * <p>A caller may decline the count - OData spells this {@code $count=false} - because on a deep-paged
 * filtered query over a large table the {@code SELECT COUNT(*)} is usually the slowest part of the
 * request. A declined count is {@code null} here and is omitted from the JSON entirely, so a client
 * that never declines sees no change. Zero is not available as a sentinel for "not counted", because
 * zero is a real total.
 *
 * @param content       this page's items, already mapped to the response model
 * @param page          zero-based index of the page this offset falls in; derived, and lossy for an
 *                      offset that is not a multiple of {@code size}
 * @param size          the page size that was applied, which may be smaller than the one requested
 * @param offset        absolute index of the first item in {@code content} within the full result set
 * @param totalElements total number of matching items across all pages, or {@code null} if the caller
 *                      declined the count
 * @param totalPages    total number of pages at this page size, or {@code null} if the caller declined
 *                      the count
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long offset,
        Long totalElements,
        Integer totalPages) {

    public PageResponse {
        content = content == null ? List.of() : List.copyOf(content);
    }

    /**
     * Wraps a Spring Data page whose contents are already response objects.
     *
     * <p>{@code Page} is the only Spring Data type this starter touches, and the dependency is
     * optional: a service that pages some other way uses {@link #of(List, long, int, Long)}, and a
     * module that never pages at all does not get spring-data-commons on its classpath because of
     * this class.
     */
    public static <T> PageResponse<T> of(Page<T> page) {
        return of(page.getContent(), page.getPageable().getOffset(), page.getSize(), page.getTotalElements());
    }

    /**
     * Wraps a page of domain objects and maps each one, which is the call a controller actually
     * makes: {@code PageResponse.of(products, mapper::toResponse)}.
     *
     * <p>It exists to keep the mapping from being written as
     * {@code PageResponse.of(page.map(mapper::toResponse))} at every call site - correct, but it
     * invites the shorter {@code PageResponse.of(page)} with entities still inside, which is how an
     * entity ends up on the wire.
     */
    public static <S, T> PageResponse<T> of(Page<S> page, Function<? super S, ? extends T> mapper) {
        return of(
                page.getContent().stream().<T>map(mapper::apply).toList(),
                page.getPageable().getOffset(),
                page.getSize(),
                page.getTotalElements());
    }

    /**
     * Builds the envelope from the parts a query actually produces, deriving {@code page} and
     * {@code totalPages}.
     *
     * <p>This is the factory for a result that is not a Spring Data {@code Page}, which includes every
     * result whose caller declined the count - a {@code Page} always carries a total and so cannot
     * represent one. It is also what keeps a module that produces such results from needing to depend
     * on spring-data-commons, or this class from needing to depend on that module.
     *
     * @param totalElements the total, or {@code null} when the caller declined the count
     */
    public static <T> PageResponse<T> of(List<T> content, long offset, int size, Long totalElements) {
        int effectiveSize = size < 1 ? 1 : size;
        int page = (int) (offset / effectiveSize);
        Integer totalPages = totalElements == null
                ? null
                : (int) ((totalElements + effectiveSize - 1) / effectiveSize);
        return new PageResponse<>(content, page, size, offset, totalElements, totalPages);
    }

    /** A single page holding everything that was found - for an endpoint that does not paginate. */
    public static <T> PageResponse<T> ofAll(List<T> content) {
        int found = content == null ? 0 : content.size();
        return new PageResponse<>(content, 0, found, 0L, (long) found, found == 0 ? 0 : 1);
    }

    /** An empty first page of the given size. */
    public static <T> PageResponse<T> empty(int size) {
        return new PageResponse<>(List.of(), 0, size, 0L, 0L, 0);
    }

    /** Applies a mapping to an envelope that has already been built. */
    public <R> PageResponse<R> map(Function<? super T, ? extends R> mapper) {
        return new PageResponse<>(
                content.stream().<R>map(mapper::apply).toList(), page, size, offset, totalElements, totalPages);
    }

    /** Whether the caller was given a total, i.e. did not decline the count. */
    public boolean hasTotal() {
        return totalElements != null;
    }
}
