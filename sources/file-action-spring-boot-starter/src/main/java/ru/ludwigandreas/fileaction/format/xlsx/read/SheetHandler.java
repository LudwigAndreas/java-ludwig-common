package ru.ludwigandreas.fileaction.format.xlsx.read;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import ru.ludwigandreas.fileaction.api.ColumnBinding;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.fileaction.exception.FileRejectedException;
import ru.ludwigandreas.fileaction.format.CellValue;
import ru.ludwigandreas.fileaction.format.RawRow;
import ru.ludwigandreas.fileaction.format.ReadBudget;
import ru.ludwigandreas.fileaction.format.RowVisitor;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Turns POI's SAX callbacks into {@link RawRow}s.
 *
 * <p>Two passes use this class with different jobs. The first stops after the header row and keeps the
 * headers; the second skips the header row and hands every data row to a {@link RowVisitor}. Each stops the
 * parse by throwing {@link StopParsing}, which is the only way to abandon a SAX parse part-way and is why a
 * control-flow exception is used here and nowhere else in the module.
 */
final class SheetHandler implements SheetContentsHandler {

    /**
     * Thrown to abandon a SAX parse.
     *
     * <p>A SAX parse has no cursor to stop advancing, so the only way out of one before the end of the
     * document is to throw from a callback. Caught by {@code XlsxRowReader} immediately around the parse,
     * never propagated. Carries no stack trace, because it is control flow rather than a failure and
     * filling one in for every early stop of every upload is pure cost.
     */
    static final class StopParsing extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StopParsing() {
            super(null, null, false, false);
        }
    }

    private final RowBinding<?> binding;
    private final ReadBudget budget;
    private final RawValueDataFormatter formatter;
    private final boolean date1904;
    private final boolean headerPassOnly;
    private final RowVisitor visitor;
    private final String sheetName;

    /** Raw header texts in file order, filled by the header pass. */
    private final List<String> headers = new ArrayList<>();

    /** Declared canonical header, by the spreadsheet column letter it was found in. */
    private final Map<String, String> headerByColumnLetter = new LinkedHashMap<>();

    private Map<String, CellValue> currentCells = new LinkedHashMap<>();
    private List<String> currentProblems = new ArrayList<>();
    private int currentDisplayedRow;
    private int dataRowsSeen;

    private SheetHandler(RowBinding<?> binding, ReadBudget budget, boolean date1904, String sheetName,
                         boolean headerPassOnly, RowVisitor visitor,
                         Map<String, String> headerByColumnLetter) {
        this.binding = binding;
        this.budget = budget;
        this.date1904 = date1904;
        this.sheetName = sheetName;
        this.headerPassOnly = headerPassOnly;
        this.visitor = visitor;
        this.formatter = new RawValueDataFormatter();
        if (headerByColumnLetter != null) {
            this.headerByColumnLetter.putAll(headerByColumnLetter);
        }
    }

    /** A handler that reads the header row and then stops. */
    static SheetHandler forHeaderPass(RowBinding<?> binding, ReadBudget budget, boolean date1904,
                                      String sheetName) {
        return new SheetHandler(binding, budget, date1904, sheetName, true, row -> false, null);
    }

    /** A handler that skips the header row and visits the data rows. */
    static SheetHandler forDataPass(RowBinding<?> binding, ReadBudget budget, boolean date1904,
                                    String sheetName, Map<String, String> resolvedHeaders,
                                    RowVisitor visitor) {
        return new SheetHandler(binding, budget, date1904, sheetName, false, visitor, resolvedHeaders);
    }

    /** The raw header texts, in file order. Filled only by a header pass. */
    List<String> headers() {
        return List.copyOf(headers);
    }

    /** The declared canonical header found in each spreadsheet column, for the data pass. */
    Map<String, String> resolvedHeaders() {
        return Map.copyOf(headerByColumnLetter);
    }

    /** The {@link RawValueDataFormatter} POI must be given, so that numbers arrive unformatted. */
    RawValueDataFormatter formatter() {
        return formatter;
    }

    @Override
    public void startRow(int rowNum) {
        currentCells = new LinkedHashMap<>();
        currentProblems = new ArrayList<>();
        // POI's rowNum is 0-based; what a user sees in the row gutter is 1-based.
        currentDisplayedRow = rowNum + 1;
    }

    @Override
    public void endRow(int rowNum) {
        boolean isHeaderRow = rowNum == 0;
        if (isHeaderRow) {
            if (headerPassOnly) {
                throw new StopParsing();
            }
            return;
        }
        if (headerPassOnly) {
            // A sheet whose first row is not row 0 - a workbook with leading blank rows - ends the header
            // pass here rather than running to the end of the file looking for a row that has gone past.
            throw new StopParsing();
        }
        dataRowsSeen++;
        if (dataRowsSeen > budget.maxRows()) {
            throw new FileRejectedException(ProblemStatus.PAYLOAD_TOO_LARGE,
                    FileActionProblemCodes.TOO_MANY_ROWS, budget.maxRows());
        }
        RawRow row = new RawRow(sheetName, currentDisplayedRow, currentCells, currentProblems);
        // A blank row is skipped rather than visited. Spreadsheets routinely carry a trailing run of them -
        // a user deleted the contents of rows rather than the rows - and reporting each as a reject would
        // bury the real problems and push the submission over its reject threshold.
        if (row.isBlank() && currentProblems.isEmpty()) {
            return;
        }
        if (!visitor.visit(row)) {
            throw new StopParsing();
        }
    }

    @Override
    public void cell(String cellReference, String formattedValue, org.apache.poi.xssf.usermodel.XSSFComment comment) {
        if (cellReference == null) {
            return;
        }
        String columnLetter = columnLetterOf(cellReference);
        if (currentDisplayedRow == 1) {
            captureHeader(columnLetter, formattedValue);
            return;
        }
        String canonicalHeader = headerByColumnLetter.get(columnLetter);
        if (canonicalHeader == null) {
            // A column the binding does not declare. Not an error: a user's sheet routinely carries columns
            // the action does not care about, and reading them would only cost memory.
            return;
        }
        if (formattedValue != null && formattedValue.length() > budget.maxCellCharacters()) {
            currentProblems.add(FileActionProblemCodes.CELL_NOT_COERCIBLE);
            formatter.reset();
            return;
        }
        CellValue value = toCellValue(formattedValue);
        if (!value.isEmpty()) {
            currentCells.put(canonicalHeader, value);
        }
        formatter.reset();
    }

    /**
     * Builds a cell from what POI handed over.
     *
     * <p>{@link RawValueDataFormatter} has already turned a numeric cell into its raw decimal string and
     * recorded whether the display format was a date format, so a numeric cell is recognised by the string
     * parsing as a number rather than by asking POI what type it was - which the SAX handler does not say.
     * A string cell that happens to contain only digits therefore becomes a numeric cell, which is correct:
     * the coercion layer wants the number either way, and nothing downstream distinguishes them.
     */
    private CellValue toCellValue(String formattedValue) {
        if (formattedValue == null || formattedValue.isBlank()) {
            return CellValue.empty();
        }
        try {
            BigDecimal number = new BigDecimal(formattedValue.strip());
            return CellValue.ofNumber(number, formatter.wasDateFormatted());
        } catch (NumberFormatException notANumber) {
            return CellValue.ofText(formattedValue);
        }
    }

    private void captureHeader(String columnLetter, String text) {
        headers.add(text == null ? "" : text);
        Optional<ColumnBinding> column = binding.columnForHeader(text);
        // putIfAbsent: a sheet carrying the same header twice is odd but not broken, and taking the later
        // one would mean the value the user is shown came from a column they are not looking at.
        column.ifPresent(bound -> headerByColumnLetter.putIfAbsent(columnLetter, bound.header()));
    }

    private static String columnLetterOf(String cellReference) {
        CellAddress address = new CellAddress(cellReference);
        return CellReference.convertNumToColString(address.getColumn());
    }

    /** Whether this workbook counts dates from 1904, which the coercion layer needs to know. */
    boolean date1904() {
        return date1904;
    }

    @Override
    public void headerFooter(String text, boolean isHeader, String tagName) {
        // A sheet's print header and footer are page furniture, not data. Ignored deliberately: the
        // interface requires the method and an empty body with no comment reads like an oversight.
    }
}
