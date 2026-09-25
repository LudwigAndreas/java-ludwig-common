package ru.ludwigandreas.export.api;

import java.util.List;

/**
 * One row, extracted and enriched, on its way to a writer.
 *
 * <p>This is the only shape that crosses the hand-off between the enrichment side of the pipeline
 * and the writing side, and it is deliberately the narrowest one that can: typed cells, positional,
 * nothing else. The writer never sees the domain row, which is what keeps a writer from reaching
 * back into the persistence context of a stream the engine has already advanced past.
 *
 * <p>Positional rather than keyed by column id because the column set is fixed for the whole sheet
 * and is known to the writer before the first row: a map per row would allocate a hash table a
 * million times to express an order that never changes.
 *
 * @param cells  the cells, in the sheet's column order; the list is exactly as long as the sheet's
 *               visible column set
 * @param sheetId which sheet this row belongs to, for a multi-sheet definition
 */
public record RenderedRow(List<CellValue> cells, String sheetId) {

    public RenderedRow {
        if (cells == null) {
            throw new IllegalArgumentException("A RenderedRow needs its cells");
        }
        cells = List.copyOf(cells);
    }

    /** A row for a single-sheet definition. */
    public static RenderedRow of(List<CellValue> cells) {
        return new RenderedRow(cells, null);
    }

    /** How many cells this row carries. */
    public int size() {
        return cells.size();
    }
}
