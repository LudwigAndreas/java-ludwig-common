package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.format.RawRow;
import ru.ludwigandreas.fileaction.format.RowReader;
import ru.ludwigandreas.fileaction.format.xlsx.read.XlsxRowReaderFactory;
import ru.ludwigandreas.fileaction.format.xlsx.write.FormulaGuard;
import ru.ludwigandreas.fileaction.format.xlsx.write.TemplateWriter;

/** The generated template, and the one property that matters: a file built from it binds. */
class TemplateWriterTest {

    @TempDir
    Path temp;

    private static final RowBinding<OrderLine> BINDING = RowBinding.of(OrderLine.class)
            .sheet("Orders")
            .column("sku", "SKU")
            .column("quantity", "Qty")
            .column("comment", "Comment").optional()
            .build();

    @Test
    @DisplayName("the template's header row is the binding's columns in declaration order")
    void writesTheDeclaredHeaders() throws IOException {
        Path template = writeTemplate(BINDING);

        try (RowReader reader = new XlsxRowReaderFactory()
                .open(template, BINDING, ReadBudgets.generous())) {
            assertThat(reader.headers()).containsExactly("SKU", "Qty", "Comment");
            assertThat(reader.sheet()).isEqualTo("Orders");
        }
    }

    @Test
    @DisplayName("a file built from the template binds, which is the whole point of the endpoint")
    void aFileBuiltFromItBinds() throws IOException {
        Path template = writeTemplate(BINDING);
        // Stand in for the user: open the template, add a row, save. Reading the generated headers back and
        // writing a file with exactly those headers is what a person does by typing underneath them.
        List<String> headers;
        try (RowReader reader = new XlsxRowReaderFactory()
                .open(template, BINDING, ReadBudgets.generous())) {
            headers = reader.headers();
        }
        Path filled = temp.resolve("filled.xlsx");
        Workbooks.write(filled, "Orders", headers, List.of(List.of("A-1", 3, "fine")));

        List<RawRow> rows = new ArrayList<>();
        try (RowReader reader = new XlsxRowReaderFactory()
                .open(filled, BINDING, ReadBudgets.generous())) {
            reader.forEachRow(row -> rows.add(row));
        }

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).cell("SKU").trimmedText()).isEqualTo("A-1");
        assertThat(rows.get(0).cell("Qty").number().intValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("a binding naming no sheet still produces a usable sheet name")
    void defaultsTheSheetName() throws IOException {
        RowBinding<OrderLine> noSheet = RowBinding.of(OrderLine.class)
                .column("sku", "SKU")
                .build();

        Path template = writeTemplate(noSheet);

        try (RowReader reader = new XlsxRowReaderFactory()
                .open(template, noSheet, ReadBudgets.generous())) {
            assertThat(reader.sheet()).isNotBlank();
        }
    }

    @Test
    @DisplayName("a header that looks like a formula is neutralised before it is written")
    void neutralisesHeaders() {
        assertThat(FormulaGuard.neutralise("=SUM(A1)")).isEqualTo("'=SUM(A1)");
    }

    private Path writeTemplate(RowBinding<?> binding) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new TemplateWriter().write(binding, bytes);
        Path file = temp.resolve("template-" + System.nanoTime() + ".xlsx");
        Files.write(file, bytes.toByteArray());
        return file;
    }
}
