package ru.ludwigandreas.db.core.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class PageableUtils {

    private PageableUtils() {
    }

    /**
     * Builds a {@link Pageable}, clamping {@code size} into {@code [1, maxSize]} and applying
     * {@code defaultSort} when {@code sort} is null or unsorted.
     *
     * @throws IllegalArgumentException if {@code page} is negative
     */
    public static Pageable of(int page, int size, int maxSize, Sort sort, Sort defaultSort) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative: " + page);
        }
        int boundedSize = Math.max(1, Math.min(size, maxSize));
        Sort effectiveSort = (sort == null || sort.isUnsorted()) ? defaultSort : sort;
        return effectiveSort == null || effectiveSort.isUnsorted()
                ? PageRequest.of(page, boundedSize)
                : PageRequest.of(page, boundedSize, effectiveSort);
    }

    public static Pageable of(int page, int size, int maxSize) {
        return of(page, size, maxSize, null, null);
    }
}
