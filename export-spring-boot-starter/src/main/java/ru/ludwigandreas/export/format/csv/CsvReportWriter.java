package ru.ludwigandreas.export.format.csv;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.export.api.CellValue;
import ru.ludwigandreas.export.api.ColumnSpec;
import ru.ludwigandreas.export.api.MultiSheetStrategy;
import ru.ludwigandreas.export.api.RenderedRow;
import ru.ludwigandreas.export.api.SheetSpec;
import ru.ludwigandreas.export.api.WriterContext;
import ru.ludwigandreas.export.format.AbstractReportWriter;
import ru.ludwigandreas.export.format.CellSanitizer;
import ru.ludwigandreas.export.format.CellTextFormatter;
import ru.ludwigandreas.export.i18n.ExportMessages;

/**
 * Writes a report as delimiter-separated text.
 *
 * <h2>Quoting</h2>
 *
 * <p>A value is quoted when it contains the delimiter, a double quote, a carriage return or a line
 * feed, and an embedded quote is doubled - RFC 4180's rule, applied in both profiles because the
 * alternative is a file that a parser silently reads as having the wrong number of columns. A value
 * is also quoted when it has leading or trailing whitespace, which is not required by the standard
 * and is the only way to stop a reader from trimming it away; a report that carries a deliberately
 * padded account number should not have it silently changed.
 *
 * <p>Quoting is independent of formula sanitisation, which happens first and for a different
 * reason. A value frequently needs both.
 *
 * <h2>Several sheets in a format that has none</h2>
 *
 * <p>Resolved by {@link MultiSheetStrategy}, and {@code REJECT} never reaches here - the registry
 * refuses that combination at startup and the request layer refuses it with a localized 400.
 *
 * <p>Under {@code ZIP} the file this writer produces is an archive with one CSV entry per sheet,
 * which is why {@link ru.ludwigandreas.export.api.ReportWriter#finish()} returns a path at all. The
 * decision is made before the first byte, from {@link WriterContext#sheets()}, because a stream that
 * has already been written cannot become an archive afterwards.
 *
 * <p>Under {@code PRIMARY_ONLY} the engine simply does not route the other sheets here, and records
 * the omission on the run. What never happens is the fourth behaviour - writing the first sheet and
 * saying nothing - which is the one every exporter grows by accident.
 */
@Slf4j
public class CsvReportWriter extends AbstractReportWriter {

    private final CsvProfile profile;
    private final ExportMessages messages;

    /** The archive, when this file is one; null for a plain CSV. */
    private final ZipOutputStream archive;

    /** The byte sink under whichever container applies. */
    private final OutputStream bytes;

    /** Character writer over the current entry, replaced per sheet when this file is an archive. */
    private Writer text;

    private List<CellTextFormatter> formatters;

    /**
     * Opens the target file.
     *
     * @param context   the run's target file, sheets, locale, options and totals decision
     * @param profile   the resolved delimiter, charset, mark and value style
     * @param messages  resolves the text of a degraded cell
     * @throws IOException if the file cannot be opened
     */
    public CsvReportWriter(WriterContext context, CsvProfile profile, ExportMessages messages)
            throws IOException {
        super(context);
        this.profile = profile;
        this.messages = messages;
        boolean zipped = context.needsSheetContainer()
                && context.multiSheetStrategy() == MultiSheetStrategy.ZIP;
        this.bytes = Files.newOutputStream(context.targetFile());
        this.archive = zipped ? new ZipOutputStream(bytes, profile.charset()) : null;
        if (!zipped) {
            this.text = newWriter(bytes);
        }
    }

    @Override
    protected void onBeginSheet(SheetSpec sheet) throws IOException {
        if (archive != null) {
            archive.putNextEntry(new ZipEntry(entryName(sheet)));
            text = newWriter(archive);
        }
        formatters = CellTextFormatter.forSheet(sheet.columns(), profile.style(),
                context().render(), messages);
        if (profile.writesByteOrderMark()) {
            text.write(CsvProfile.BOM);
        }
        writeHeader(sheet);
    }

    @Override
    protected void onWriteRow(RenderedRow row) throws IOException {
        writeCells(row.cells());
    }

    @Override
    protected void onEndSheet(List<CellValue> totals) throws IOException {
        if (!totals.isEmpty()) {
            writeCells(totals);
        }
        if (archive != null) {
            // Flush the character writer into the entry before closing it; the writer is not closed,
            // because closing it would close the whole archive underneath.
            text.flush();
            archive.closeEntry();
            text = null;
        }
    }

    @Override
    protected Path onFinish() throws IOException {
        if (archive != null) {
            archive.finish();
        } else {
            text.flush();
        }
        return context().targetFile();
    }

    @Override
    public void close() {
        // Every close is best-effort and none of them may mask the failure that brought the run
        // here: the engine deletes the temp file on the failure path anyway, so a close that threw
        // would replace a diagnosable error with one about a stream.
        closeQuietly(text);
        closeQuietly(archive);
        closeQuietly(bytes);
    }

    private void writeHeader(SheetSpec sheet) throws IOException {
        List<ColumnSpec> columns = sheet.columns();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                text.write(profile.delimiter());
            }
            // Headers come from the message bundles, which a service controls - but a bundle value is
            // still text in a cell, and applying the sanitizer consistently costs nothing.
            writeField(CellSanitizer.sanitize(columns.get(i).header()));
        }
        text.write(CsvProfile.LINE_ENDING);
    }

    private void writeCells(List<CellValue> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                text.write(profile.delimiter());
            }
            CellValue value = cells.get(i);
            writeField(textOf(value, formatters.get(i).format(value)));
        }
        text.write(CsvProfile.LINE_ENDING);
    }

    /**
     * Writes one field, quoting it if it has to be.
     *
     * <p>Written straight through to the buffered writer rather than assembled into a line first.
     * At the design point this runs twenty-five million times, and the common case - a value that
     * needs no quoting - then allocates nothing at all.
     */
    private void writeField(String value) throws IOException {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (!needsQuoting(value)) {
            text.write(value);
            return;
        }
        text.write('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                text.write('"');
            }
            text.write(c);
        }
        text.write('"');
    }

    private boolean needsQuoting(String value) {
        if (Character.isWhitespace(value.charAt(0))
                || Character.isWhitespace(value.charAt(value.length() - 1))) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == profile.delimiter() || c == '"' || c == '\r' || c == '\n') {
                return true;
            }
        }
        return false;
    }

    private Writer newWriter(OutputStream target) {
        return new BufferedWriter(new OutputStreamWriter(target, profile.charset()));
    }

    /** A sheet's entry name inside the archive, with the sheet id as the file name. */
    private String entryName(SheetSpec sheet) {
        return sheet.id() + "." + context().format().fileExtension();
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            log.warn("Could not close CSV output for run {}: {}", context().runId(), e.toString());
        } catch (Exception e) {
            log.warn("Could not close CSV output for run {}", context().runId(), e);
        }
    }
}
