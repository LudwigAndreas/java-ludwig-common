package ru.ludwigandreas.export.format.xlsx;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import ru.ludwigandreas.export.api.CellFormat;
import ru.ludwigandreas.export.api.CellValue;
import ru.ludwigandreas.export.api.ColumnSpec;
import ru.ludwigandreas.export.api.MetadataEntry;
import ru.ludwigandreas.export.api.RenderedRow;
import ru.ludwigandreas.export.api.SheetSpec;
import ru.ludwigandreas.export.api.WriterContext;
import ru.ludwigandreas.export.format.AbstractReportWriter;
import ru.ludwigandreas.export.format.CellSanitizer;
import ru.ludwigandreas.export.i18n.ExportMessages;

/**
 * Writes a report as an Office Open XML workbook, streaming.
 *
 * <h2>SXSSF, and what it costs</h2>
 *
 * <p>POI's streaming workbook keeps only a configurable window of rows in memory and flushes the
 * rest to its own temporary files as it goes. That is the only way a million-row sheet fits in a
 * fixed heap, and it comes with two consequences this writer has to live with rather than work
 * around:
 *
 * <ul>
 *   <li><b>A flushed row cannot be revisited.</b> So the totals row is folded as the rows go past
 *       and written at the end, never computed by reading back what was written.</li>
 *   <li><b>A column cannot be auto-sized.</b> {@code autoSizeColumn} measures every cell of the
 *       column, which means holding them all - exactly what the streaming avoids. Widths are
 *       therefore declared on the column, and a definition that forgets gets a default rather than
 *       a column of {@code ####}.</li>
 * </ul>
 *
 * <p>SXSSF's own temp files are released by {@code dispose()} in {@link #close()}. Failing to call
 * it is how a reporting instance quietly fills its disk with POI's leftovers even though this
 * module's own temp files are all accounted for.
 *
 * <h2>Typed cells</h2>
 *
 * <p>A date is written as a date, money as a number with a currency format, a boolean as a boolean.
 * The whole point of producing XLSX rather than CSV is that the recipient can sort, filter and sum
 * the result; a column of numbers written as text supports none of those, and looks identical until
 * somebody tries.
 *
 * <p>The one honest limitation: a numeric cell in XLSX is an IEEE-754 double, by specification. A
 * {@code BigDecimal} carrying more than about fifteen significant digits cannot be represented
 * exactly, and this writer does not pretend otherwise - it writes the nearest double, which is what
 * every spreadsheet would do with the value anyway. A report that needs more precision than that
 * needs a column of text, and should declare one.
 *
 * <h2>Rollover</h2>
 *
 * <p>A sheet holds at most 1,048,576 rows including its header. Past that, this writer opens a
 * continuation sheet and keeps going, rather than truncating or failing: a report sized for a
 * million rows is one parameter change away from exceeding the limit, and a file that stops at the
 * ceiling without saying so is the failure this module exists to prevent.
 */
@Slf4j
public class XlsxReportWriter extends AbstractReportWriter {

    /** What the OOXML specification allows per sheet, header included. */
    public static final long SPEC_MAX_ROWS_PER_SHEET = 1_048_576L;

    /** Width of the metadata sheet's label column, in characters. */
    private static final int METADATA_LABEL_WIDTH = 28;

    /** Width of the metadata sheet's value column: wide enough for a parameter list on one line. */
    private static final int METADATA_VALUE_WIDTH = 64;

    private final SXSSFWorkbook workbook;
    private final XlsxStyles styles;
    private final ExportMessages messages;
    private final boolean freezeHeader;
    private final boolean metadataSheet;
    private final String continuedLabel;

    /**
     * Rows this writer puts on one sheet before rolling over.
     *
     * <p>Read from the format's own {@code FormatCapabilities} rather than hard-coded, so that the
     * ceiling a definition was validated against at startup and the ceiling the writer enforces are
     * the same number. It also makes the rollover testable without writing a million rows.
     */
    private final int maxRowsPerSheet;

    /** Sheet names already in the workbook, so a collision is impossible rather than unlikely. */
    private final Set<String> usedNames = new LinkedHashSet<>();

    private SXSSFSheet sheet;
    private int rowInSheet;
    private int partOfSheet;

    /**
     * Opens the workbook.
     *
     * @param context        the run's target file, sheets, locale, options and totals decision
     * @param flushWindow    rows kept in memory per sheet before POI flushes them
     * @param freezeHeader   whether the header row is frozen and an autofilter applied
     * @param metadataSheet  whether the provenance sheet is written
     * @param template       the branding workbook to write into, already opened, or empty
     * @param messages       resolves a degraded cell's marker and the continuation label
     * @throws IOException if the template cannot be read
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - constructed in exactly one place, by the factory, which
    // is where the configuration is read; there is no positional call site for the rule to protect.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public XlsxReportWriter(WriterContext context, int flushWindow, boolean freezeHeader,
                            boolean metadataSheet, Optional<InputStream> template,
                            ExportMessages messages) throws IOException {
        super(context);
        this.workbook = openWorkbook(template, flushWindow);
        this.styles = new XlsxStyles(workbook);
        this.messages = messages;
        this.freezeHeader = freezeHeader;
        this.metadataSheet = metadataSheet;
        this.continuedLabel = messages.resolve("ludwig.export.metadata.continued",
                context.render().locale());
        this.maxRowsPerSheet = (int) Math.min(Integer.MAX_VALUE,
                context.format().capabilities().maxRowsPerSheet());
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            usedNames.add(workbook.getSheetName(i));
        }
    }

    @Override
    protected void onBeginSheet(SheetSpec spec) throws IOException {
        partOfSheet = 1;
        startSheet(SheetNames.of(spec.title(), usedNames), spec.columns());
    }

    @Override
    protected void onWriteRow(RenderedRow row) throws IOException {
        if (rowInSheet >= maxRowsPerSheet) {
            rollOver();
        }
        Row target = sheet.createRow(rowInSheet++);
        List<ColumnSpec> columns = openSheet().columns();
        for (int i = 0; i < columns.size(); i++) {
            writeCell(target.createCell(i), row.cells().get(i), columns.get(i),
                    XlsxStyles.Emphasis.NORMAL);
        }
    }

    @Override
    protected void onEndSheet(List<CellValue> totals) throws IOException {
        if (totals.isEmpty()) {
            return;
        }
        if (rowInSheet >= maxRowsPerSheet) {
            rollOver();
        }
        Row target = sheet.createRow(rowInSheet++);
        List<ColumnSpec> columns = openSheet().columns();
        Cell label = target.createCell(0);
        label.setCellValue(messages.resolve("ludwig.export.totals.label", context().render().locale()));
        label.setCellStyle(styles.styleFor(columns.get(0).format(), XlsxStyles.Emphasis.TOTAL, null));
        for (int i = 1; i < columns.size(); i++) {
            writeCell(target.createCell(i), totals.get(i), columns.get(i), XlsxStyles.Emphasis.TOTAL);
        }
    }

    @Override
    protected Path onFinish() throws IOException {
        if (metadataSheet && !context().metadata().isEmpty()) {
            writeMetadataSheet();
        }
        try (OutputStream out = Files.newOutputStream(context().targetFile())) {
            workbook.write(out);
        }
        log.debug("Report run {} wrote an XLSX workbook with {} sheet(s) and {} cell style(s)",
                context().runId(), workbook.getNumberOfSheets(), styles.size());
        return context().targetFile();
    }

    @Override
    public void close() {
        try {
            // Releases POI's own temporary files. This module accounts for the file it created and
            // POI accounts for its own; skipping this leaks the second set, which is the larger of
            // the two while a large sheet is being written.
            workbook.dispose();
            workbook.close();
        } catch (IOException e) {
            log.warn("Could not release the XLSX workbook for run {}: {}", context().runId(), e.toString());
        }
    }

    private SXSSFWorkbook openWorkbook(Optional<InputStream> template, int flushWindow)
            throws IOException {
        if (template.isEmpty()) {
            return new SXSSFWorkbook(flushWindow);
        }
        try (InputStream in = template.get()) {
            // A template's own sheets stay in memory, which is correct and cheap: a branding
            // workbook is a handful of formatted rows. Only the sheets this writer creates stream.
            return new SXSSFWorkbook(new XSSFWorkbook(in), flushWindow);
        }
    }

    private void startSheet(String name, List<ColumnSpec> columns) {
        sheet = workbook.createSheet(name);
        usedNames.add(name);
        rowInSheet = 0;
        Row header = sheet.createRow(rowInSheet++);
        for (int i = 0; i < columns.size(); i++) {
            ColumnSpec column = columns.get(i);
            Cell cell = header.createCell(i);
            cell.setCellValue(column.header());
            cell.setCellStyle(styles.styleFor(column.format(), XlsxStyles.Emphasis.HEADER, null));
            sheet.setColumnWidth(i, XlsxStyles.widthOf(column.width()));
        }
        if (freezeHeader) {
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, Math.max(0, columns.size() - 1)));
        }
    }

    /**
     * Starts a continuation sheet and repeats the header on it.
     *
     * <p>The header is repeated rather than carried over, because a continuation sheet is opened in
     * a workbook the recipient reads one sheet at a time: a second sheet of unlabelled columns is
     * unusable on its own, and "look at the previous tab" is not a thing a spreadsheet can express.
     */
    private void rollOver() {
        partOfSheet++;
        String name = SheetNames.continuation(openSheet().title() + " " + continuedLabel,
                partOfSheet, usedNames);
        log.info("Report run {} rolled sheet '{}' over onto '{}' at the format's {}-row ceiling",
                context().runId(), openSheet().title(), name, maxRowsPerSheet);
        startSheet(name, openSheet().columns());
    }

    private void writeCell(Cell cell, CellValue value, ColumnSpec column,
                           XlsxStyles.Emphasis emphasis) {
        Currency perRow = value instanceof CellValue.Money money ? money.currency() : null;
        cell.setCellStyle(styles.styleFor(column.format(), emphasis, perRow));
        if (value instanceof CellValue.Text text) {
            cell.setCellValue(textOf(value, text.value()));
        } else if (value instanceof CellValue.Number number) {
            cell.setCellValue(doubleOf(number.value()));
        } else if (value instanceof CellValue.Money money) {
            cell.setCellValue(doubleOf(money.amount()));
        } else if (value instanceof CellValue.Date date) {
            cell.setCellValue(date.value());
        } else if (value instanceof CellValue.DateTime dateTime) {
            cell.setCellValue(LocalDateTime.ofInstant(dateTime.value(), dateTime.zone()));
        } else if (value instanceof CellValue.Bool flag) {
            cell.setCellValue(flag.value());
        } else if (value instanceof CellValue.Error error) {
            // A degraded cell is text, and it goes through the sanitizer like any other text: a
            // marker resolved from a bundle a service controls is still text in a spreadsheet.
            cell.setCellValue(textOf(value,
                    messages.resolve(error.messageKey(), context().render().locale(), error.args())));
        } else {
            cell.setBlank();
        }
    }

    /**
     * Writes the sheet that makes a file defensible months later.
     *
     * <p>Last, so it does not push the data sheet out of first position, and always present when the
     * format allows it. Everything a person can be asked about a spreadsheet they were sent - which
     * report, which version of it, who ran it, with what parameters and filter, how many rows, what
     * was incomplete - is answerable from inside the file rather than from a run record they do not
     * have access to.
     */
    private void writeMetadataSheet() {
        String title = messages.resolve("ludwig.export.metadata.sheet-title", context().render().locale());
        Sheet meta = workbook.createSheet(SheetNames.of(title, usedNames));
        usedNames.add(meta.getSheetName());
        List<MetadataEntry> entries = new ArrayList<>(context().metadata());
        // The facts that only exist once the run is over - row count, elapsed time, degraded stages,
        // dropped sheets. Appended rather than merged, so the sheet reads chronologically: what was
        // asked for, then what happened.
        entries.addAll(lateMetadata());
        int rowIndex = 0;
        for (MetadataEntry entry : entries) {
            Row row = meta.createRow(rowIndex++);
            Cell label = row.createCell(0);
            label.setCellValue(entry.label());
            label.setCellStyle(styles.styleFor(CellFormat.text(),
                    XlsxStyles.Emphasis.HEADER, null));
            Cell value = row.createCell(1);
            value.setCellValue(CellSanitizer.sanitize(entry.value()));
            value.setCellStyle(styles.styleFor(CellFormat.text(),
                    XlsxStyles.Emphasis.NORMAL, null));
        }
        meta.setColumnWidth(0, XlsxStyles.widthOf(METADATA_LABEL_WIDTH));
        meta.setColumnWidth(1, XlsxStyles.widthOf(METADATA_VALUE_WIDTH));
    }

    /**
     * A cell value as the format can actually hold it.
     *
     * <p>{@code doubleValue()} rather than an exactness check, because XLSX has no other numeric
     * type: rejecting a value a spreadsheet would happily accept would fail reports over a
     * limitation of the file format rather than of the data. See the class comment.
     */
    private static double doubleOf(BigDecimal value) {
        return value.doubleValue();
    }

    /** How many distinct cell styles this workbook has created, for the test that guards the ceiling. */
    int styleCount() {
        return styles.size();
    }

    /** The sheet count, including continuations and the metadata sheet. */
    int sheetCount() {
        return workbook.getNumberOfSheets();
    }
}
