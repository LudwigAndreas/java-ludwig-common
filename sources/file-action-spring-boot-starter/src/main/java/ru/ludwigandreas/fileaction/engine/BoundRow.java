package ru.ludwigandreas.fileaction.engine;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.ludwigandreas.fileaction.api.RowAddress;

/**
 * One bound row, with the address it came from.
 *
 * <h2>Why the address travels with the row</h2>
 *
 * <p>The row record a handler sees is the author's own type and carries no sheet or row number - correctly, since
 * a handler's job is the domain and not the spreadsheet. But a handler can <em>reject</em> a row, and a reject
 * with no row number is useless: "this SKU is unknown" without "row 14" leaves the user to find it.
 *
 * <p>The first version of the bound-row artifact wrote the payload alone, which meant a handler's reject could
 * not be addressed and - because {@code RejectCollector} ignores a reject with no problems - was silently not
 * counted at all. {@code CommitPolicyTest.perRowAppliesTheRest} caught it. So the artifact carries both, and the
 * address is what {@code ApplyPass} attaches to whatever the handler says.
 *
 * @param sheet   the sheet the row came from, or null for a CSV
 * @param row     the 1-based displayed row number
 * @param payload the author's row record
 * @param <R>     the row type
 */
public record BoundRow<R>(@JsonProperty("sheet") String sheet, @JsonProperty("row") int row,
                          @JsonProperty("payload") R payload) {

    /** Rejects a bound row that cannot be addressed. */
    public BoundRow {
        if (row < 1) {
            throw new IllegalArgumentException("a BoundRow's row is 1-based as the spreadsheet shows it,"
                    + " was " + row);
        }
    }

    /** The address, for a problem about this row. */
    public RowAddress address() {
        return RowAddress.ofRow(sheet, row);
    }
}
