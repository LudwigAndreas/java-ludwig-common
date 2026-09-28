package ru.ludwigandreas.export.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Turns a stream of rendered rows into a file. One instance per run and format, used by one thread.
 *
 * <h2>The contract is a state machine, and it is deliberately small</h2>
 *
 * <pre>
 * create -&gt; ( beginSheet -&gt; writeRow* -&gt; endSheet )+ -&gt; finish -&gt; close
 * </pre>
 *
 * <p>Five methods, because a writer is the one part of this module a service is expected to
 * implement from scratch - PDF and ODS are explicitly left to a later module, and the seam only
 * holds if implementing it is a day's work rather than a study of the engine.
 *
 * <h2>Single-threaded, and why</h2>
 *
 * <p>The engine calls one writer from one thread for the life of a run. That is not a limitation
 * being tolerated; it is what makes a streaming writer possible at all. A streaming workbook flushes
 * rows to disk in file order, so concurrent writers would have to be serialised somewhere anyway,
 * and doing it here would mean every implementation carried a lock it did not choose. The
 * parallelism in this pipeline is upstream, in enrichment, where it actually buys something - the
 * partner calls, not the bytes, are what a report waits on.
 *
 * <p>The consequence is that a writer must not read ahead or buffer unboundedly: it is the slowest
 * stage for CSV, and the bounded hand-off queue in front of it is what applies backpressure to the
 * source. A writer that accepted rows into an unbounded queue would remove that backpressure and
 * the engine's memory bound with it.
 *
 * <h2>Sanitisation is not optional</h2>
 *
 * <p>Text cells are passed through the shared formula sanitizer before they reach a file. This is
 * implemented once in the base class every shipped writer extends rather than left to each writer,
 * because a format that forgets it ships a spreadsheet that executes a cell on open - and "the CSV
 * writer does it and the XLSX writer does not" is the exact shape that mistake takes in practice.
 */
public interface ReportWriter extends AutoCloseable {

    /**
     * Starts a sheet. Called once per sheet, in the order the rows will arrive.
     *
     * @param sheet the sheet's resolved title and visible columns
     * @throws IOException if the sheet cannot be started
     */
    void beginSheet(SheetSpec sheet) throws IOException;

    /**
     * Writes one row into the sheet currently open.
     *
     * @param row a row whose cell count matches the open sheet's column count
     * @throws IOException if the row cannot be written
     */
    void writeRow(RenderedRow row) throws IOException;

    /**
     * Ends the sheet currently open, writing its totals row if the run asked for one.
     *
     * @param totals the aggregated cells, in the sheet's column order, or an empty list when the run
     *               writes no totals row. Computed by the engine as the rows streamed past, so a
     *               writer never makes a second pass over its own output
     * @throws IOException if the sheet cannot be finished
     */
    void endSheet(List<CellValue> totals) throws IOException;

    /**
     * Adds facts about the run that were not known when the writer was created.
     *
     * <p>Called once, immediately before {@link #finish}, with the row count, the elapsed time, the
     * stages that degraded and the sheets that were dropped. Those cannot be in
     * {@link WriterContext#metadata()} because none of them exists until the last row has been
     * written, and a metadata sheet without them answers only the questions that were easy.
     *
     * <p>A default no-op, because it is a statement a format may have nowhere to put: a CSV file is
     * one table of data and has no room for a second one. Such a writer ignores this rather than
     * inventing a place, which is the same reason {@code FormatCapabilities} carries
     * {@code metadataSheet} at all.
     *
     * @param entries the late facts, already localized and formatted
     */
    default void addMetadata(List<MetadataEntry> entries) {
        // A format with nowhere to put them says so by not overriding this.
    }

    /**
     * Completes the file.
     *
     * <p>Everything that has to happen exactly once for the document - a metadata sheet, a workbook
     * flush, a trailing newline - happens here rather than in {@link #close()}, so that a failure
     * has somewhere to be reported. {@code close()} runs on the failure path too and cannot
     * distinguish "finished" from "abandoned".
     *
     * @return the file that was written; normally {@code WriterContext.targetFile()}, and the return
     *         exists for a writer that produces an archive beside it, as the CSV writer does under
     *         {@link MultiSheetStrategy#ZIP}
     * @throws IOException if the file cannot be completed
     */
    Path finish() throws IOException;

    /**
     * Releases everything held, whether the run succeeded or not.
     *
     * <p>Must be safe to call after {@link #finish}, and must be safe to call instead of it. A
     * streaming workbook holds its own temp files; failing to release them on an abandoned run is
     * how a reporting instance fills its disk over a weekend.
     */
    @Override
    void close();
}
