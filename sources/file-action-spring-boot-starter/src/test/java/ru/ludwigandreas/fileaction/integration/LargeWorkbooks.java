package ru.ludwigandreas.fileaction.integration;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import ru.ludwigandreas.fileaction.api.RowBinding;

/** The large-workbook fixture and binding both heap tests use. */
final class LargeWorkbooks {

    /** The module's declared row ceiling, which these tests measure against. */
    static final int LARGE_ROWS = 100_000;

    /** The binding the fixture is read with. */
    static final RowBinding<HeapRow> BINDING = RowBinding.of(HeapRow.class)
            .sheet("Orders")
            .column("sku", "SKU")
            .column("quantity", "Qty")
            .column("note", "Note").optional()
            .build();

    private LargeWorkbooks() {
    }

    /** The row the fixture binds to. */
    record HeapRow(String sku, Integer quantity, String note) {
    }

    /**
     * Writes a workbook of {@code rows} data rows.
     *
     * <p>Through {@code SXSSFWorkbook} with a small window, so that generating a hundred-thousand-row fixture does
     * not itself need the heap these tests exist to prove is not needed.
     *
     * @param target where to write
     * @param rows   how many data rows
     * @throws IOException if it cannot be written
     */
    static void write(Path target, int rows) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            SXSSFSheet sheet = workbook.createSheet("Orders");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("SKU");
            header.createCell(1).setCellValue("Qty");
            header.createCell(2).setCellValue("Note");
            for (int i = 1; i <= rows; i++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(i);
                row.createCell(0).setCellValue("A-" + i);
                // Modulo 97 rather than i: a hundred thousand distinct values would make the shared-strings and
                // styles tables grow with the row count, which is the one cost a streaming read cannot avoid and
                // would muddy what the measurement measures.
                row.createCell(1).setCellValue(i % 97);
                row.createCell(2).setCellValue("note " + (i % 97));
            }
            try (OutputStream out = Files.newOutputStream(target)) {
                workbook.write(out);
            }
            workbook.dispose();
        }
    }
}
