package ru.ludwigandreas.fileaction.format;

import java.util.List;
import java.util.Map;
import ru.ludwigandreas.fileaction.api.RowAddress;

/**
 * One row of the submitted file, with its cells already resolved against the binding's headers.
 *
 * <p>The resolution happens once, in the reader, against the header row - so this carries no column
 * indices and nothing downstream of here can be broken by a user inserting a column. That is the whole
 * reason the map is keyed by the binding's canonical header rather than by position.
 *
 * @param sheet        the sheet name, or null for a CSV
 * @param displayedRow the 1-based row number the spreadsheet shows, so a message can be navigated to
 * @param cells        the cells of columns the binding declares, keyed by canonical header. A declared
 *                     column the file carries no value for is absent rather than present-and-empty,
 *                     because "the user left it blank" and "the column is not in this file" are
 *                     different problems with different messages
 * @param problems     structural problems the reader found with this row that are not about any one
 *                     cell - a ragged row, an over-long cell. Carried rather than thrown so the row
 *                     still reaches the reject report with its address
 */
public record RawRow(String sheet, int displayedRow, Map<String, CellValue> cells,
                     List<String> problems) {

    /** Defensively copies and rejects a row that cannot be addressed. */
    public RawRow {
        if (displayedRow < 1) {
            throw new IllegalArgumentException(
                    "A RawRow's displayedRow is 1-based as the spreadsheet shows it, was " + displayedRow);
        }
        cells = cells == null ? Map.of() : Map.copyOf(cells);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /**
     * The cell for a declared column.
     *
     * @param header the binding's canonical header
     * @return the cell, or {@link CellValue#empty()} when the file has no value there
     */
    public CellValue cell(String header) {
        return cells.getOrDefault(header, CellValue.empty());
    }

    /** This row's address, for a problem about the row rather than one of its cells. */
    public RowAddress address() {
        return RowAddress.ofRow(sheet, displayedRow);
    }

    /**
     * The address of one of this row's cells.
     *
     * @param header the binding's canonical header
     * @return the address
     */
    public RowAddress address(String header) {
        return RowAddress.ofCell(sheet, displayedRow, header);
    }

    /** Whether every declared column of this row is empty, which is what a trailing blank row looks like. */
    public boolean isBlank() {
        return cells.values().stream().allMatch(CellValue::isEmpty);
    }
}
