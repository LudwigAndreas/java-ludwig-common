package ru.ludwigandreas.fileaction.unit;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Builds workbook fixtures.
 *
 * <p>Written with {@code SXSSFWorkbook}, the streaming writer, for the same reason the module reads with
 * {@code XSSFReader}: a hundred-thousand-row fixture built with the DOM writer would need the heap this
 * module's tests exist to prove is not needed. This class is test-only and sits outside the packages
 * {@code PoiConfinementTest} guards, which is why it may name SXSSF at all - the confinement rule covers
 * {@code src/main}, where a DOM read would be a production defect.
 */
final class Workbooks {

    private Workbooks() {
    }

    /**
     * Writes a workbook with one sheet, a header row, and the given data rows.
     *
     * @param target    where to write
     * @param sheetName the sheet name
     * @param headers   the header row
     * @param rows      the data rows; a {@code null} leaves the cell empty, a {@code Number} writes a
     *                  numeric cell and anything else writes its {@code toString} as text
     * @throws IOException if the file cannot be written
     */
    static void write(Path target, String sheetName, List<String> headers, List<List<Object>> rows)
            throws IOException {
        writeWithDateFormat(target, sheetName, headers, rows, null);
    }

    /**
     * As {@link #write}, additionally applying a date display format to any {@code java.time.LocalDate}
     * cell, so that the reader sees a date-formatted numeric cell rather than a plain number.
     *
     * @param target     where to write
     * @param sheetName  the sheet name
     * @param headers    the header row
     * @param rows       the data rows
     * @param dateFormat an Excel format string such as {@code dd.mm.yyyy}, or null for none
     * @throws IOException if the file cannot be written
     */
    static void writeWithDateFormat(Path target, String sheetName, List<String> headers,
                                    List<List<Object>> rows, String dateFormat) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            SXSSFSheet sheet = workbook.createSheet(sheetName);
            CellStyle dateStyle = null;
            if (dateFormat != null) {
                CreationHelper helper = workbook.getCreationHelper();
                dateStyle = workbook.createCellStyle();
                dateStyle.setDataFormat(helper.createDataFormat().getFormat(dateFormat));
            }
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            for (int r = 0; r < rows.size(); r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
                List<Object> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    Object value = values.get(c);
                    if (value == null) {
                        continue;
                    }
                    org.apache.poi.ss.usermodel.Cell cell = row.createCell(c);
                    if (value instanceof Number number) {
                        cell.setCellValue(number.doubleValue());
                    } else if (value instanceof java.time.LocalDate date) {
                        cell.setCellValue(date);
                        if (dateStyle != null) {
                            cell.setCellStyle(dateStyle);
                        }
                    } else {
                        cell.setCellValue(value.toString());
                    }
                }
            }
            try (OutputStream out = Files.newOutputStream(target)) {
                workbook.write(out);
            }
            workbook.dispose();
        }
    }
}
