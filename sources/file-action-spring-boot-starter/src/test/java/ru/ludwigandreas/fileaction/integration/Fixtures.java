package ru.ludwigandreas.fileaction.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/** Workbook bytes for the integration tests. */
final class Fixtures {

    private Fixtures() {
    }

    /**
     * A workbook of order lines.
     *
     * @param rows each row's SKU and quantity
     * @return the bytes
     * @throws IOException if the workbook cannot be written
     */
    static byte[] orders(List<Object[]> rows) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            SXSSFSheet sheet = workbook.createSheet("Orders");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("SKU");
            header.createCell(1).setCellValue("Qty");
            header.createCell(2).setCellValue("Note");
            for (int i = 0; i < rows.size(); i++) {
                Object[] values = rows.get(i);
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue((String) values[0]);
                row.createCell(1).setCellValue(((Number) values[1]).doubleValue());
                if (values.length > 2 && values[2] != null) {
                    row.createCell(2).setCellValue((String) values[2]);
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            workbook.write(bytes);
            workbook.dispose();
            return bytes.toByteArray();
        }
    }

    /**
     * A workbook of {@code count} order lines, SKU {@code A-1} upward.
     *
     * @param count how many rows
     * @return the bytes
     * @throws IOException if the workbook cannot be written
     */
    static byte[] orders(int count) throws IOException {
        List<Object[]> rows = new java.util.ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            rows.add(new Object[] {"A-" + i, i, "note " + i});
        }
        return orders(rows);
    }
}
