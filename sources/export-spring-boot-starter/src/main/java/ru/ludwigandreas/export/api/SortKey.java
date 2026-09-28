package ru.ludwigandreas.export.api;

/**
 * One term of the order the rows are read in.
 *
 * <p>The engine appends the source's primary key to whatever the request asked for, because keyset
 * pagination needs a total order: two rows that compare equal under the requested sort would be
 * returned by one page and skipped by the next, and the resulting file would be missing rows with
 * nothing anywhere to say so. That appended term is not represented here - it is the source's
 * business - but it is why a {@code RowSource} may never honour a sort it was given without adding
 * its own tiebreaker.
 *
 * @param columnId  the column being sorted on; validated at startup against the definition's columns
 *                  and against the source's declared sortable set
 * @param direction which way
 */
public record SortKey(String columnId, SortDirection direction) {

    public SortKey {
        if (columnId == null || columnId.isBlank()) {
            throw new IllegalArgumentException("A SortKey needs a column id");
        }
        if (direction == null) {
            throw new IllegalArgumentException("SortKey " + columnId + " needs a direction");
        }
    }

    /** Ascending on this column. */
    public static SortKey asc(String columnId) {
        return new SortKey(columnId, SortDirection.ASC);
    }

    /** Descending on this column. */
    public static SortKey desc(String columnId) {
        return new SortKey(columnId, SortDirection.DESC);
    }
}
