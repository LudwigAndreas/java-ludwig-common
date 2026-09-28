package ru.ludwigandreas.odatafilter.core;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * OData's {@code $skip} is an absolute row offset that need not be a multiple of {@code $top},
 * so it cannot always be represented as a page-number-based {@link org.springframework.data.domain.PageRequest}.
 * This implements {@link Pageable} directly against an arbitrary offset, which Spring Data JPA
 * translates to {@code setFirstResult}/{@code setMaxResults} regardless of page alignment.
 */
final class OffsetPageRequest implements Pageable {

    private final long offset;
    private final int limit;
    private final Sort sort;

    OffsetPageRequest(long offset, int limit, Sort sort) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.offset = offset;
        this.limit = limit;
        this.sort = sort;
    }

    @Override
    public int getPageNumber() {
        return (int) (offset / limit);
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return sort;
    }

    @Override
    public Pageable next() {
        return new OffsetPageRequest(offset + limit, limit, sort);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetPageRequest(Math.max(0, offset - limit), limit, sort) : first();
    }

    @Override
    public Pageable first() {
        return new OffsetPageRequest(0, limit, sort);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetPageRequest((long) pageNumber * limit, limit, sort);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
