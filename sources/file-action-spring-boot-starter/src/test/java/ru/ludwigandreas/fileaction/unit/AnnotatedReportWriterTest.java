package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.format.RawRow;
import ru.ludwigandreas.fileaction.format.RowReader;
import ru.ludwigandreas.fileaction.format.xlsx.read.XlsxRowReaderFactory;
import ru.ludwigandreas.fileaction.format.xlsx.write.AnnotatedReportWriter;

/** The annotated copy: the user's own sheet, plus a column saying what to fix. */
class AnnotatedReportWriterTest {

    @TempDir
    Path temp;

    /** A binding over the annotated copy, so the appended column can be read back as data. */
    private static final RowBinding<AnnotatedRow> ANNOTATED = RowBinding.of(AnnotatedRow.class)
            .sheet("Orders")
            .column("sku", "SKU")
            .column("quantity", "Qty")
            .column("problems", "Problems").optional()
            .build();

    /** A row of the annotated copy. */
    record AnnotatedRow(String sku, Integer quantity, String problems) {
    }

    @Test
    @DisplayName("the copy keeps the user's columns and appends the problems for the rejected rows only")
    void annotatesTheRejectedRows() throws IOException {
        Path submitted = submitted(List.of(
                List.of("A-1", 3),
                List.of("A-2", 0),
                List.of("A-3", 5)));

        List<RawRow> annotated = annotate(submitted, Map.of(3, "Qty must be positive"));

        assertThat(annotated).hasSize(3);
        assertThat(annotated.get(0).cell("Problems").isEmpty()).isTrue();
        assertThat(annotated.get(1).cell("Problems").trimmedText()).isEqualTo("Qty must be positive");
        assertThat(annotated.get(2).cell("Problems").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("the original values survive the copy, so a fixed file can be re-submitted as it stands")
    void keepsTheOriginalValues() throws IOException {
        Path submitted = submitted(List.of(List.of("A-1", 3)));

        List<RawRow> annotated = annotate(submitted, Map.of());

        assertThat(annotated.get(0).cell("SKU").trimmedText()).isEqualTo("A-1");
        assertThat(annotated.get(0).cell("Qty").trimmedText()).isEqualTo("3");
    }

    @Test
    @DisplayName("the problems column lands in the same place on every row")
    void theProblemsColumnIsFixed() throws IOException {
        // Deliberately ragged: the second row is wider than the header. An earlier version put the problem
        // text one past the widest row seen so far, which moved the column part-way down the sheet.
        Path submitted = temp.resolve("ragged.xlsx");
        Workbooks.write(submitted, "Orders", List.of("SKU", "Qty"),
                List.of(List.of("A-1", 3), List.of("A-2", 4, "stray", "stray")));

        List<RawRow> annotated = annotate(submitted, Map.of(2, "first", 3, "second"));

        assertThat(annotated.get(0).cell("Problems").trimmedText()).isEqualTo("first");
        assertThat(annotated.get(1).cell("Problems").trimmedText())
                .as("both rows' problems must be readable under the same header")
                .isEqualTo("second");
    }

    @Test
    @DisplayName("a value out of the user's file that looks like a formula is neutralised in the copy")
    void neutralisesCopiedValues() throws IOException {
        Path submitted = submitted(List.of(List.of("=HYPERLINK(\"http://example.invalid\")", 1)));

        List<RawRow> annotated = annotate(submitted, Map.of());

        assertThat(annotated.get(0).cell("SKU").trimmedText())
                .as("the person who opens the report is usually not the person who uploaded the file")
                .startsWith("'=");
    }

    @Test
    @DisplayName("a problem message that looks like a formula is neutralised too")
    void neutralisesTheProblemText() throws IOException {
        Path submitted = submitted(List.of(List.of("A-1", 1)));

        List<RawRow> annotated = annotate(submitted, Map.of(2, "=1+1"));

        assertThat(annotated.get(0).cell("Problems").trimmedText()).isEqualTo("'=1+1");
    }

    private Path submitted(List<List<Object>> rows) throws IOException {
        Path file = temp.resolve("submitted-" + System.nanoTime() + ".xlsx");
        Workbooks.write(file, "Orders", List.of("SKU", "Qty"), rows);
        return file;
    }

    private List<RawRow> annotate(Path submitted, Map<Integer, String> problemsByRow)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new AnnotatedReportWriter().write(submitted, "Orders", problemsByRow, "Problems", bytes);
        Path report = temp.resolve("report-" + System.nanoTime() + ".xlsx");
        Files.write(report, bytes.toByteArray());

        List<RawRow> rows = new ArrayList<>();
        try (RowReader reader = new XlsxRowReaderFactory()
                .open(report, ANNOTATED, ReadBudgets.generous())) {
            reader.forEachRow(row -> rows.add(row));
        }
        return rows;
    }
}
