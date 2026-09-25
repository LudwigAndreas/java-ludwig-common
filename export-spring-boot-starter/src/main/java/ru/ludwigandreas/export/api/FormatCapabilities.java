package ru.ludwigandreas.export.api;

/**
 * What a format can carry, declared by its writer factory and checked three times.
 *
 * <p>A format is not a rendering detail: CSV has no sheets, no styles and no notion of a cell type,
 * and a definition built around a metadata sheet and a totals row produces a materially different
 * document in CSV than its author reviewed. The three checks are deliberate and each catches the
 * mismatch at a different distance from the user:
 *
 * <ul>
 *   <li><b>At startup</b>, the registry refuses a definition that allows a format which cannot carry
 *       it - multi-sheet plus CSV under {@link MultiSheetStrategy#REJECT}, or a {@code maxRows}
 *       above {@link #maxRowsPerSheet()} with no rollover. The author learns about it while they
 *       are still holding the definition.</li>
 *   <li><b>At request time</b>, a requester asking for a format the definition does not allow gets a
 *       localized 400 naming the formats that are allowed.</li>
 *   <li><b>At write time</b>, a capability the run opted to do without is recorded on the run and in
 *       the metadata, so a file that lost its totals row says so.</li>
 * </ul>
 *
 * <p>What never happens is a silent downgrade. A writer that quietly drops a sheet, a style or a
 * totals row produces a file that looks complete and is not, and the recipient has no way to tell.
 *
 * @param multiSheet      whether the format has more than one sheet at all
 * @param styling         whether cells can carry fonts, borders and fills
 * @param totalsRow       whether a totals row is meaningful; false for formats a machine parses,
 *                        where an extra row at the bottom is a corrupt record
 * @param metadataSheet   whether the run's provenance can be carried inside the file
 * @param typedCells      whether a cell has a type of its own, as opposed to everything being text
 * @param maxRowsPerSheet the hard ceiling one sheet can hold; {@link #UNLIMITED_ROWS} for a format
 *                        with none
 */
public record FormatCapabilities(
        boolean multiSheet,
        boolean styling,
        boolean totalsRow,
        boolean metadataSheet,
        boolean typedCells,
        long maxRowsPerSheet) {

    /** {@link #maxRowsPerSheet()} for a format with no structural row ceiling, such as CSV. */
    public static final long UNLIMITED_ROWS = Long.MAX_VALUE;

    public FormatCapabilities {
        if (maxRowsPerSheet < 1) {
            throw new IllegalArgumentException(
                    "FormatCapabilities.maxRowsPerSheet must be at least 1, was: " + maxRowsPerSheet);
        }
        if (!multiSheet && metadataSheet) {
            throw new IllegalArgumentException(
                    "A format with no sheets cannot carry a metadata sheet");
        }
    }

    /** A full-featured spreadsheet format: sheets, styles, typed cells, a row ceiling. */
    public static FormatCapabilities spreadsheet(long maxRowsPerSheet) {
        return new FormatCapabilities(true, true, true, true, true, maxRowsPerSheet);
    }

    /** A flat text format: one table, everything a string, no ceiling. */
    public static FormatCapabilities flatText() {
        return new FormatCapabilities(false, false, false, false, false, UNLIMITED_ROWS);
    }
}
