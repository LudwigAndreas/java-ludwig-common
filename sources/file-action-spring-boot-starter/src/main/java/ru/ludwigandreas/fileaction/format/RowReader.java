package ru.ludwigandreas.fileaction.format;

import java.io.Closeable;
import java.util.List;

/**
 * Reads a submitted file into rows, lazily.
 *
 * <h2>The contract that matters: nothing is materialised</h2>
 *
 * <p>An implementation hands each row to a {@link RowVisitor} as it reads it. It must not read the file
 * into a list, must not call {@code readAllBytes}, and must not hold more than the current row plus
 * whatever the format forces it to - for XLSX that is the shared-strings table, which POI materialises
 * whatever the reader does, which is why {@link ReadBudget#maxSharedStringBytes()} exists and is checked
 * from the ZIP directory before any XML is parsed.
 *
 * <p>{@code NoMaterialisationTest} enforces this inside the module. It cannot see inside an implementation
 * written elsewhere, which is why this is not an extension point: the two formats are a closed set, and a
 * service needing a third brings it here, where the budget and the confinement tests apply to it. That is
 * the opposite of {@code file-ingest}'s {@code RecordParser}, which is an extension point on purpose
 * because the formats a partner drops cannot be enumerated in advance.
 *
 * <h2>Why this is not {@code file-ingest}'s {@code RecordParser}</h2>
 *
 * <p>They look alike and are not interchangeable. {@code RecordParser} reports absolute <em>byte
 * offsets</em> for every record and declares a {@code CheckpointKind}, because an ingest resumes a
 * half-read object with a ranged GET. Neither has any meaning for a file somebody is waiting for: nothing
 * resumes mid-file, and a byte offset is not something to show a user. What this contract carries instead -
 * a sheet name, a displayed row number, cells keyed by header - is exactly what {@code RecordParser} has no
 * vocabulary for, because its output goes into a staging table and this one's goes onto a screen.
 *
 * <p>So the platform has two reader contracts on purpose, and no shared {@code file-format-core}:
 * {@code file-ingest} ships no concrete parser at all, so there is nothing to consolidate, and a module
 * extracted for a single consumer is speculative generality. The condition that would change that - a
 * second module needing to <em>read</em> CSV or XLSX - is recorded in this change's design, so it stays a
 * decision rather than becoming a drift.
 */
public interface RowReader extends Closeable {

    /**
     * The sheet being read.
     *
     * @return the sheet name, or null for a format with no sheets
     */
    String sheet();

    /**
     * The header texts found in the file, in file order, exactly as they appear.
     *
     * <p>Raw rather than normalised, because this is what a missing-column message quotes back: telling
     * somebody their file lacks {@code sku} when the header they are looking at says {@code SKU } is a
     * worse message than quoting what is there.
     *
     * <p>Available as soon as the reader is open. For a SAX-parsed workbook that means the header row has
     * already been read - the parse is started at open and abandoned after the first row - so that a file
     * missing a required column is refused before any of its hundred thousand data rows are touched.
     *
     * @return the headers
     */
    List<String> headers();

    /**
     * Reads the data rows, handing each to {@code visitor}.
     *
     * <p>Called once. An implementation may assume single-threaded traversal.
     *
     * @param visitor what each row goes to; returning false from it stops the read
     */
    void forEachRow(RowVisitor visitor);
}
