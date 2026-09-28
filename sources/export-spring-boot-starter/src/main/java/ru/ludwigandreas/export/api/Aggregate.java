package ru.ludwigandreas.export.api;

/**
 * The totals-row function a column contributes, computed as the rows stream past.
 *
 * <p>Every one of these is computable in a single pass over the column with constant memory, and
 * that is the criterion for being here rather than a matter of which functions happen to be useful.
 * A median or a distinct count would require holding the column, which at the design point is a
 * million values the engine has otherwise been careful never to accumulate; a report that needs one
 * computes it in the query that feeds the {@code RowSource}, where the database can.
 *
 * <p>Aggregates are declared on the column rather than on the sheet so that a column subset chosen
 * at request time carries its own totals with it - a totals row declared once for a fixed set of
 * columns would either disappear or misalign as soon as a user narrowed the selection.
 */
public enum Aggregate {

    /** No totals cell for this column; the cell is left empty. */
    NONE,

    /** Arithmetic sum. Numeric formats only; a money column sums per currency and rejects a mix. */
    SUM,

    /** Arithmetic mean over the rows that had a value; empty cells do not count towards the divisor. */
    AVERAGE,

    /** Smallest value seen. Defined for numeric and temporal formats. */
    MIN,

    /** Largest value seen. Defined for numeric and temporal formats. */
    MAX,

    /** How many rows carried a value in this column. Defined for every format. */
    COUNT
}
