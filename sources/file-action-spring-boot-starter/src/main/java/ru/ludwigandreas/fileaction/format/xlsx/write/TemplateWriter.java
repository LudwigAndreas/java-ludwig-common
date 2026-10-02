package ru.ludwigandreas.fileaction.format.xlsx.write;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import ru.ludwigandreas.fileaction.api.ColumnBinding;
import ru.ludwigandreas.fileaction.api.RowBinding;

/**
 * Writes a blank workbook whose header row is generated from a {@link RowBinding}.
 *
 * <h2>Why a template endpoint is worth its code</h2>
 *
 * <p>The commonest reason an import fails is a header that does not match - a column renamed, a column
 * missing, a sheet called something else. Every one of those is prevented by handing the user a file that is
 * already correct, and because the header row comes from the same binding the reader validates against, the
 * two cannot drift apart. A template maintained by hand in a wiki can and does.
 *
 * <p>Required columns are marked in bold and optional ones are not, which is the whole of the legend: a
 * template that needed an explanation would not have saved anybody anything.
 */
public class TemplateWriter {

    /** How wide a generated column is, in POI's units of 1/256 of a character. */
    private static final int COLUMN_WIDTH = 256 * 18;

    /** The streaming window. A template has one row, so this only bounds the writer's own buffer. */
    private static final int ROW_WINDOW = 100;

    /**
     * Writes the template for a binding.
     *
     * @param binding the binding whose columns become the header row
     * @param target  where to write; closed by the caller
     * @throws IOException if the workbook cannot be written
     */
    public void write(RowBinding<?> binding, OutputStream target) throws IOException {
        // SXSSF, the streaming writer, for consistency with the reader rather than from necessity: a
        // template is one row. PoiConfinementTest allows SXSSF only in this package, and a second writer
        // elsewhere using the DOM one would be the thing that rule exists to catch.
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_WINDOW)) {
            String sheetName = binding.sheet() == null ? "Data" : binding.sheet();
            SXSSFSheet sheet = workbook.createSheet(sheetName);
            CellStyle requiredStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            requiredStyle.setFont(bold);

            Row header = sheet.createRow(0);
            List<ColumnBinding> columns = binding.columns();
            for (int i = 0; i < columns.size(); i++) {
                ColumnBinding column = columns.get(i);
                Cell cell = header.createCell(i);
                // Neutralised even here. A header is author-declared rather than user-supplied, so this is
                // belt and braces - but a binding whose column header a service built from configuration is
                // exactly the path by which author-declared stops meaning trusted.
                cell.setCellValue(FormulaGuard.neutralise(column.header()));
                if (column.required()) {
                    cell.setCellStyle(requiredStyle);
                }
                sheet.setColumnWidth(i, COLUMN_WIDTH);
            }
            workbook.write(target);
            workbook.dispose();
        }
    }
}
