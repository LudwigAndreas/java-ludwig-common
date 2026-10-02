package ru.ludwigandreas.fileaction.format.xlsx.write;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.fileaction.exception.FileRejectedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Copies a submitted workbook and appends a column saying what was wrong with each row.
 *
 * <h2>Why the submitted file rather than a list of problems</h2>
 *
 * <p>A user who uploaded four hundred rows and got twelve rejects needs to fix twelve rows in the file they
 * already have. A report listing row numbers and messages makes them do the join by hand, scrolling between
 * two windows; a copy of their own sheet with a {@code Problems} column makes the fix obvious and the
 * re-upload immediate - and because the copy still has their columns in their order, the fixed file binds
 * without further work.
 *
 * <p>It is a copy rather than an edit of the original: the original is the record of what was submitted and
 * is kept untouched for exactly that reason.
 *
 * <h2>Bounded, like everything else</h2>
 *
 * <p>Read through {@code XSSFReader}'s SAX path and written through {@code SXSSFWorkbook}'s flushing window,
 * so what is resident is one row in and one row out. The alternative - load the workbook, add a column, save
 * it - is the DOM read this module forbids, arrived at by the back door of a reporting feature.
 */
public class AnnotatedReportWriter {

    /** The streaming window: how many written rows stay in memory before being flushed to disk. */
    private static final int ROW_WINDOW = 200;

    /**
     * Writes the annotated copy.
     *
     * @param workbook        the submitted workbook
     * @param sheetName       the sheet that was read
     * @param problemsByRow   the rendered problem text for each rejected displayed row number, already
     *                        localised: a writer is not the place locale is resolved, and a report may be
     *                        produced on a worker thread with no request bound to it
     * @param problemsHeader  the header for the appended column, already localised
     * @param target          where to write; closed by the caller
     * @throws IOException if the copy cannot be written
     */
    public void write(Path workbook, String sheetName, Map<Integer, String> problemsByRow,
                      String problemsHeader, OutputStream target) throws IOException {
        try (OPCPackage container = OPCPackage.open(workbook.toFile(), PackageAccess.READ);
                SXSSFWorkbook out = new SXSSFWorkbook(ROW_WINDOW)) {
            XSSFReader reader = new XSSFReader(container);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(container);
            SXSSFSheet sheet = out.createSheet(sheetName == null ? "Data" : sheetName);
            CellStyle problemStyle = out.createCellStyle();
            Font red = out.createFont();
            red.setBold(true);
            red.setColor(IndexedColors.DARK_RED.getIndex());
            problemStyle.setFont(red);

            CopyingHandler handler = new CopyingHandler(sheet, problemsByRow, problemsHeader,
                    problemStyle);
            copySheet(reader, strings, sheetName, handler);
            out.write(target);
            out.dispose();
        } catch (OpenXML4JException | SAXException unreadable) {
            throw new FileRejectedException(ProblemStatus.INVALID, FileActionProblemCodes.UNREADABLE,
                    unreadable, "xlsx");
        }
    }

    private static void copySheet(XSSFReader reader, ReadOnlySharedStringsTable strings,
                                  String sheetName, CopyingHandler handler)
            throws IOException, OpenXML4JException, SAXException {
        XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
        while (sheets.hasNext()) {
            try (java.io.InputStream source = sheets.next()) {
                if (sheetName != null && !sheetName.equals(sheets.getSheetName())) {
                    continue;
                }
                XMLReader parser = org.apache.poi.util.XMLHelper.newXMLReader();
                parser.setContentHandler(new XSSFSheetXMLHandler(reader.getStylesTable(), null,
                        strings, handler, new org.apache.poi.ss.usermodel.DataFormatter(), false));
                parser.parse(new InputSource(source));
                return;
            } catch (javax.xml.parsers.ParserConfigurationException misconfigured) {
                throw new IOException("the XML reader could not be created", misconfigured);
            }
        }
    }

    /**
     * Writes each row it is handed straight out again, with the problems column appended.
     *
     * <p>Values are copied as the <em>formatted</em> text POI produces, not as raw numbers, because this
     * file is for a person to read: a date must look like the date they typed, not like 46054. That is the
     * opposite of the reading path's choice, and for the opposite reason - there, the number is what matters
     * because a machine consumes it.
     */
    private static final class CopyingHandler implements SheetContentsHandler {

        private final SXSSFSheet sheet;
        private final Map<Integer, String> problemsByRow;
        private final String problemsHeader;
        private final CellStyle problemStyle;
        private Row current;

        /**
         * The column the problems go in, fixed from the header row's width.
         *
         * <p>Fixed, not per row. An earlier version used the widest row seen so far, which put the problem
         * text in a different column on different rows as the sheet got wider - unreadable in a spreadsheet,
         * which is the one thing this file has to be. A data row whose values extend past the last header
         * column is overwritten here, and that is accepted: a value under no header was not read by the
         * import either, so the annotated copy is not losing anything the action used.
         */
        private int problemsColumn = -1;

        /** The widest column seen in the row currently being read. */
        private int widestInRow;

        CopyingHandler(SXSSFSheet sheet, Map<Integer, String> problemsByRow, String problemsHeader,
                       CellStyle problemStyle) {
            this.sheet = sheet;
            this.problemsByRow = problemsByRow;
            this.problemsHeader = problemsHeader;
            this.problemStyle = problemStyle;
        }

        @Override
        public void startRow(int rowNum) {
            current = sheet.createRow(rowNum);
        }

        @Override
        public void endRow(int rowNum) {
            if (current == null) {
                return;
            }
            int displayedRow = rowNum + 1;
            if (rowNum == 0) {
                problemsColumn = widestInRow + 1;
                write(problemsColumn, problemsHeader);
            } else if (problemsColumn >= 0) {
                String problems = problemsByRow.get(displayedRow);
                if (problems != null) {
                    write(problemsColumn, problems);
                }
            }
            widestInRow = 0;
            current = null;
        }

        private void write(int column, String text) {
            Cell cell = current.createCell(column);
            cell.setCellValue(FormulaGuard.neutralise(text));
            cell.setCellStyle(problemStyle);
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (current == null || cellReference == null) {
                return;
            }
            int column = new CellAddress(cellReference).getColumn();
            widestInRow = Math.max(widestInRow, column);
            if (formattedValue != null) {
                // Every value written back is neutralised, because every one of them came out of a file a
                // user chose and is going into a file somebody else will open.
                current.createCell(column).setCellValue(FormulaGuard.neutralise(formattedValue));
            }
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // Print furniture, not data. Ignored deliberately.
        }
    }
}
