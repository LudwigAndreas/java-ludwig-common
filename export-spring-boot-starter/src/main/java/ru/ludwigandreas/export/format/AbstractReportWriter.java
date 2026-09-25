package ru.ludwigandreas.export.format;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import ru.ludwigandreas.export.api.CellValue;
import ru.ludwigandreas.export.api.RenderedRow;
import ru.ludwigandreas.export.api.MetadataEntry;
import ru.ludwigandreas.export.api.ReportWriter;
import ru.ludwigandreas.export.api.SheetSpec;
import ru.ludwigandreas.export.api.WriterContext;

/**
 * The half of a writer that is the same for every format: the state machine, the width check, and
 * formula sanitisation.
 *
 * <h2>Why sanitisation is here and not in each writer</h2>
 *
 * <p>Because the failure mode is a format that forgets, and "the CSV writer does it and the XLSX
 * writer does not" is the exact shape the mistake takes in practice - the risk reads as a CSV
 * problem, and a string cell in a workbook is just as executable. {@link #textOf} is the only route
 * from a {@link CellValue} to a string in the shipped writers, and it sanitises. A format added
 * outside this module can bypass it, which is why {@link CellSanitizer} is public and documented;
 * what it cannot do is extend this class and bypass it by accident.
 *
 * <h2>What is sanitised, and what deliberately is not</h2>
 *
 * <p>Only genuinely textual cells - {@link CellValue.Text} and {@link CellValue.Error}. A number,
 * date or boolean is rendered by this module and cannot carry an injected formula, and prefixing its
 * rendering would be actively wrong: a negative amount written as {@code '-5} arrives in the
 * recipient's spreadsheet as the text {@code -5}, in a column they wanted to sum.
 *
 * <h2>The state machine</h2>
 *
 * <pre>
 * create -&gt; ( beginSheet -&gt; writeRow* -&gt; endSheet )+ -&gt; finish -&gt; close
 * </pre>
 *
 * <p>Enforced here rather than trusted, because the engine is not the only caller a writer will ever
 * have - a service implementing a format writes a test that drives it directly - and a writer that
 * silently accepted a row before its sheet was open would produce a file whose first rows landed
 * somewhere unpredictable.
 */
public abstract class AbstractReportWriter implements ReportWriter {

    /** Everything this writer was told at creation. */
    private final WriterContext writerContext;

    /**
     * Facts the engine adds once the last row is written - the row count, the elapsed time, the
     * degraded stages.
     *
     * <p>Not static, and not shared: two concurrent runs each have their own writer, and a shared list
     * would put one report's row count on another's metadata sheet.
     */
    private final List<MetadataEntry> lateMetadata = new ArrayList<>();

    private SheetSpec openSheet;
    private boolean finished;
    private int sheetsWritten;

    protected AbstractReportWriter(WriterContext writerContext) {
        if (writerContext == null) {
            throw new IllegalArgumentException("A writer needs its WriterContext");
        }
        this.writerContext = writerContext;
    }

    /** Everything this writer was told at creation. */
    protected final WriterContext context() {
        return writerContext;
    }

    /** The sheet currently open, or null between sheets. */
    protected final SheetSpec openSheet() {
        return openSheet;
    }

    /**
     * The late metadata, for a subclass that has somewhere to write it.
     *
     * <p>Collected here rather than in each writer so that the engine has exactly one call to make and
     * a writer that ignores it cannot forget to accept it. Never null; empty until the engine adds to
     * it, which it does once, just before {@code finish}.
     *
     * @return the entries, in the order the engine supplied them
     */
    protected final List<MetadataEntry> lateMetadata() {
        return List.copyOf(lateMetadata);
    }

    @Override
    public final void addMetadata(List<MetadataEntry> entries) {
        if (entries != null) {
            lateMetadata.addAll(entries);
        }
    }

    /** How many sheets have been completed so far. */
    protected final int sheetsWritten() {
        return sheetsWritten;
    }

    @Override
    public final void beginSheet(SheetSpec sheet) throws IOException {
        if (finished) {
            throw new IllegalStateException("beginSheet after finish on run " + writerContext.runId());
        }
        if (openSheet != null) {
            throw new IllegalStateException(
                    "beginSheet('" + sheet.id() + "') while sheet '" + openSheet.id() + "' is still open");
        }
        openSheet = sheet;
        onBeginSheet(sheet);
    }

    @Override
    public final void writeRow(RenderedRow row) throws IOException {
        if (openSheet == null) {
            throw new IllegalStateException("writeRow with no sheet open on run " + writerContext.runId());
        }
        int expected = openSheet.columns().size();
        if (row.size() != expected) {
            // A width mismatch means the engine and the writer disagree about the column set, which
            // would otherwise present as a file whose columns are shifted from some row onwards -
            // readable, plausible and wrong.
            throw new IllegalStateException("Row has " + row.size() + " cells but sheet '"
                    + openSheet.id() + "' has " + expected + " columns");
        }
        onWriteRow(row);
    }

    @Override
    public final void endSheet(List<CellValue> totals) throws IOException {
        if (openSheet == null) {
            throw new IllegalStateException("endSheet with no sheet open on run " + writerContext.runId());
        }
        List<CellValue> effective = totals == null ? List.of() : totals;
        if (!effective.isEmpty() && effective.size() != openSheet.columns().size()) {
            throw new IllegalStateException("Totals row has " + effective.size() + " cells but sheet '"
                    + openSheet.id() + "' has " + openSheet.columns().size() + " columns");
        }
        onEndSheet(writerContext.totalsRow() ? effective : List.of());
        openSheet = null;
        sheetsWritten++;
    }

    @Override
    public final Path finish() throws IOException {
        if (openSheet != null) {
            throw new IllegalStateException(
                    "finish while sheet '" + openSheet.id() + "' is still open");
        }
        if (finished) {
            throw new IllegalStateException("finish called twice on run " + writerContext.runId());
        }
        finished = true;
        return onFinish();
    }

    /** Starts a sheet. */
    protected abstract void onBeginSheet(SheetSpec sheet) throws IOException;

    /** Writes one row of the open sheet. */
    protected abstract void onWriteRow(RenderedRow row) throws IOException;

    /** Ends the open sheet, writing {@code totals} when the list is not empty. */
    protected abstract void onEndSheet(List<CellValue> totals) throws IOException;

    /** Completes the file and returns it. */
    protected abstract Path onFinish() throws IOException;

    /**
     * A cell's text, neutralised if a spreadsheet would execute it.
     *
     * @param value    the typed cell, which decides whether neutralisation applies at all
     * @param rendered the cell as this format renders it
     * @return the string to write
     */
    protected static String textOf(CellValue value, String rendered) {
        boolean textual = value instanceof CellValue.Text || value instanceof CellValue.Error;
        return textual ? CellSanitizer.sanitize(rendered) : rendered;
    }
}
