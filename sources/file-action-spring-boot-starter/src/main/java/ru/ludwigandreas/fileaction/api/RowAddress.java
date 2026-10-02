package ru.ludwigandreas.fileaction.api;

/**
 * Where in the submitted file something is, in the terms the person who submitted it can see.
 *
 * <h2>Why the column is a header name and not an index</h2>
 *
 * <p>"Column 3" is unactionable to a user looking at a spreadsheet, and it is wrong as soon as
 * somebody inserts a column: every mapping shifts and every message points one column to the left of
 * the problem. "Column {@code SKU}" survives the insertion and is what the user is looking at.
 *
 * <p>This is also why nothing in this module addresses a column by index after binding. The reader
 * resolves indices once, against the header row, and everything downstream speaks names.
 *
 * @param sheet  the sheet name, or {@code null} for a CSV, which has no sheets
 * @param row    the 1-based row number <em>as the spreadsheet application displays it</em>, including
 *               the header row, so that a user can navigate to it. Not a 0-based data-row ordinal:
 *               the off-by-one between those two is the single most common complaint about an import
 *               error report
 * @param column the column's header name as declared by the binding, or {@code null} when the problem
 *               is the whole row rather than one cell
 */
public record RowAddress(String sheet, int row, String column) {

    /** Rejects an address that cannot be navigated to. */
    public RowAddress {
        if (row < 1) {
            throw new IllegalArgumentException(
                    "A RowAddress row is 1-based as the spreadsheet displays it, was " + row);
        }
    }

    /**
     * An address naming a whole row rather than one of its cells.
     *
     * @param sheet the sheet name, or {@code null} for a CSV
     * @param row   the 1-based displayed row number
     * @return the address
     */
    public static RowAddress ofRow(String sheet, int row) {
        return new RowAddress(sheet, row, null);
    }

    /**
     * An address naming one cell.
     *
     * @param sheet  the sheet name, or {@code null} for a CSV
     * @param row    the 1-based displayed row number
     * @param column the column's declared header name
     * @return the address
     */
    public static RowAddress ofCell(String sheet, int row, String column) {
        return new RowAddress(sheet, row, column);
    }

    /** Whether this address names a cell rather than a whole row. */
    public boolean isCell() {
        return column != null;
    }
}
