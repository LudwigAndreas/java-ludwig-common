package ru.ludwigandreas.example.catalog.web.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Envelope for paged results.
 *
 * <p>Spring Data's own {@code Page} is not returned directly: its JSON shape is an implementation
 * detail of the persistence library and changes between versions, which is exactly the kind of
 * coupling the three-model split exists to prevent.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
