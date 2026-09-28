package ru.ludwigandreas.webcore.web;

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
 * <p>Five members, all of them things a client can act on: what came back, which page it is, how big
 * a page is, and how much there is in total.
 *
 * @param content       this page's items, already mapped to the response model
 * @param page          zero-based index of this page
 * @param size          the page size that was applied, which may be smaller than the one requested
 * @param totalElements total number of matching items across all pages
 * @param totalPages    total number of pages at this page size
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public PageResponse {
        content = content == null ? List.of() : List.copyOf(content);
    }

    /**
     * Wraps a Spring Data page whose contents are already response objects.
     *
     * <p>{@code Page} is the only Spring Data type this starter touches, and the dependency is
     * optional: a service that pages some other way can use the canonical constructor, and a module
     * that never pages at all does not get spring-data-commons on its classpath because of this
     * class.
     */
    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
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
        return new PageResponse<>(
                page.getContent().stream().<T>map(mapper::apply).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    /** A single page holding everything that was found - for an endpoint that does not paginate. */
    public static <T> PageResponse<T> ofAll(List<T> content) {
        return new PageResponse<>(content, 0, content == null ? 0 : content.size(),
                content == null ? 0 : content.size(), content == null || content.isEmpty() ? 0 : 1);
    }

    /** An empty first page of the given size. */
    public static <T> PageResponse<T> empty(int size) {
        return new PageResponse<>(List.of(), 0, size, 0, 0);
    }

    /** Applies a mapping to an envelope that has already been built. */
    public <R> PageResponse<R> map(Function<? super T, ? extends R> mapper) {
        return new PageResponse<>(
                content.stream().<R>map(mapper::apply).toList(), page, size, totalElements, totalPages);
    }
}
