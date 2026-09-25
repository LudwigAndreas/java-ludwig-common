package ru.ludwigandreas.export.api;

/**
 * A column as a writer sees it: everything needed to write the header, the cells and the total, and
 * nothing that would let a writer reach back into the domain.
 *
 * <p>{@link Column} is generic in the row type, which would make every writer generic in a type it
 * has no use for. Projecting it to this record at the start of a run keeps the writer interface
 * free of type parameters and, more usefully, removes the extractor from the writer's reach - a
 * writer that could call an extractor could re-read a row the engine has already released, which is
 * how a streaming exporter reacquires the memory profile it was written to avoid.
 *
 * @param id        the column id, for diagnostics and for the metadata sheet
 * @param header    the header text, <em>already resolved</em> against the run's locale
 * @param format    how cells in this column are presented; also the style-cache key
 * @param width     declared width in characters
 * @param aggregate the totals-row function this column contributes
 * @param pii       whether the column's values are personal data, so a writer that logs a sample
 *                  does not log this one
 */
public record ColumnSpec(String id, String header, CellFormat format, int width,
                         Aggregate aggregate, boolean pii) {

    public ColumnSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A ColumnSpec needs an id");
        }
        if (header == null) {
            throw new IllegalArgumentException("ColumnSpec " + id + " needs a resolved header");
        }
        if (format == null) {
            throw new IllegalArgumentException("ColumnSpec " + id + " needs a format");
        }
        if (width < 1) {
            throw new IllegalArgumentException("ColumnSpec " + id + " has a width below 1: " + width);
        }
        aggregate = aggregate == null ? Aggregate.NONE : aggregate;
    }
}
