package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.fileaction.exception.FileRejectedException;
import ru.ludwigandreas.fileaction.format.RawRow;
import ru.ludwigandreas.fileaction.format.RowReader;
import ru.ludwigandreas.fileaction.format.csv.CsvRowReaderFactory;

/** Reading delimited text: the delimiter, the mark, the quotes, the ragged rows and the ceilings. */
class CsvRowReaderTest {

    @TempDir
    Path temp;

    private final CsvRowReaderFactory factory = new CsvRowReaderFactory();

    /** The UTF-8 mark Excel writes on every CSV it saves, built from its code point so it is visible here. */
    private static final String BYTE_ORDER_MARK = String.valueOf((char) 0xFEFF);

    private static final RowBinding<OrderLine> BINDING = RowBinding.of(OrderLine.class)
            .column("sku", "SKU").aliases("Article")
            .column("quantity", "Qty")
            .column("comment", "Comment").optional()
            .build();

    @Test
    @DisplayName("a comma-delimited file reads")
    void readsComma() throws IOException {
        List<RawRow> rows = read("SKU,Qty\nA-1,3\nA-2,5\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).cell("SKU").trimmedText()).isEqualTo("A-1");
        assertThat(rows.get(0).cell("Qty").trimmedText()).isEqualTo("3");
    }

    @Test
    @DisplayName("a semicolon-delimited file reads without being configured for it")
    void sniffsSemicolon() throws IOException {
        List<RawRow> rows = read("SKU;Qty\nA-1;3\n");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).cell("Qty").trimmedText()).isEqualTo("3");
    }

    @Test
    @DisplayName("a tab-delimited file reads too")
    void sniffsTab() throws IOException {
        List<RawRow> rows = read("SKU\tQty\nA-1\t3\n");

        assertThat(rows.get(0).cell("SKU").trimmedText()).isEqualTo("A-1");
    }

    @Test
    @DisplayName("a byte-order mark does not become part of the first header")
    void stripsTheByteOrderMark() throws IOException {
        List<RawRow> rows = read(BYTE_ORDER_MARK + "SKU,Qty\nA-1,3\n");

        assertThat(rows.get(0).cell("SKU").trimmedText())
                .as("with the mark left on, 'SKU' is not matched and the required column looks missing")
                .isEqualTo("A-1");
    }

    @Test
    @DisplayName("a quoted field containing the delimiter stays one field")
    void honoursQuotes() throws IOException {
        List<RawRow> rows = read("SKU,Qty,Comment\nA-1,3,\"red, large\"\n");

        assertThat(rows.get(0).cell("Comment").trimmedText()).isEqualTo("red, large");
    }

    @Test
    @DisplayName("a quoted field containing a newline stays one row")
    void honoursEmbeddedNewlines() throws IOException {
        List<RawRow> rows = read("SKU,Qty,Comment\nA-1,3,\"two\nlines\"\n");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).cell("Comment").trimmedText()).isEqualTo("two\nlines");
    }

    @Test
    @DisplayName("a quoted header whose quotes contain a semicolon does not fool the delimiter sniff")
    void quotedHeaderDoesNotFoolTheSniffer() throws IOException {
        List<RawRow> rows = read("\"SKU; code\",Qty\nA-1,3\n");

        assertThat(rows.get(0).cell("Qty").trimmedText())
                .as("counting the quoted semicolon would make this semicolon-delimited and split wrongly")
                .isEqualTo("3");
    }

    @Test
    @DisplayName("an alias binds to the declared column")
    void bindsAliases() throws IOException {
        List<RawRow> rows = read("Article,Qty\nA-1,3\n");

        assertThat(rows.get(0).cell("SKU").trimmedText()).isEqualTo("A-1");
    }

    @Test
    @DisplayName("an undeclared column is ignored rather than read")
    void ignoresUndeclaredColumns() throws IOException {
        List<RawRow> rows = read("SKU,Warehouse,Qty\nA-1,W3,3\n");

        assertThat(rows.get(0).cells()).containsOnlyKeys("SKU", "Qty");
    }

    @Test
    @DisplayName("inserting a column shifts nothing, because binding is by header name")
    void insertingAColumnIsHarmless() throws IOException {
        List<RawRow> withExtra = read("SKU,Inserted,Qty\nA-1,x,3\n");

        assertThat(withExtra.get(0).cell("Qty").trimmedText()).isEqualTo("3");
    }

    @Test
    @DisplayName("a row with the wrong number of values carries a ragged-row problem and keeps its address")
    void reportsRaggedRows() throws IOException {
        List<RawRow> rows = read("SKU,Qty\nA-1,3,extra\n");

        assertThat(rows.get(0).problems()).contains(FileActionProblemCodes.RAGGED_ROW);
        assertThat(rows.get(0).displayedRow()).isEqualTo(2);
    }

    @Test
    @DisplayName("the displayed row number counts the header, so it matches what the user sees")
    void rowNumbersIncludeTheHeader() throws IOException {
        List<RawRow> rows = read("SKU,Qty\nA-1,3\nA-2,5\n");

        assertThat(rows).extracting(RawRow::displayedRow).containsExactly(2, 3);
    }

    @Test
    @DisplayName("a csv has no sheet")
    void hasNoSheet() throws IOException {
        Path file = write("SKU,Qty\nA-1,3\n");
        try (RowReader reader = factory.open(file, BINDING, ReadBudgets.generous())) {
            assertThat(reader.sheet()).isNull();
            assertThat(reader.headers()).containsExactly("SKU", "Qty");
        }
    }

    @Test
    @DisplayName("reading past the row ceiling stops and refuses, rather than letting it be advisory")
    void enforcesTheRowCeiling() throws IOException {
        StringBuilder csv = new StringBuilder("SKU,Qty\n");
        for (int i = 0; i < 10; i++) {
            csv.append("A-").append(i).append(",1\n");
        }
        Path file = write(csv.toString());

        assertThatThrownBy(() -> {
            try (RowReader reader = factory.open(file, BINDING, ReadBudgets.withMaxRows(3))) {
                reader.forEachRow(row -> true);
            }
        })
                .isInstanceOf(FileRejectedException.class)
                .hasFieldOrPropertyWithValue("code", FileActionProblemCodes.TOO_MANY_ROWS);
    }

    @Test
    @DisplayName("a visitor that returns false stops the read")
    void visitorCanStop() throws IOException {
        Path file = write("SKU,Qty\nA-1,1\nA-2,2\nA-3,3\n");
        List<RawRow> seen = new ArrayList<>();
        try (RowReader reader = factory.open(file, BINDING, ReadBudgets.generous())) {
            reader.forEachRow(row -> {
                seen.add(row);
                return seen.size() < 2;
            });
        }

        assertThat(seen).hasSize(2);
    }

    @Test
    @DisplayName("an over-long cell is a row problem rather than a read that allocates it")
    void enforcesTheCellCeiling() throws IOException {
        Path file = write("SKU,Qty\n" + "x".repeat(50) + ",3\n");
        List<RawRow> rows = new ArrayList<>();
        try (RowReader reader = factory.open(file, BINDING, ReadBudgets.withMaxCellCharacters(10))) {
            reader.forEachRow(row -> rows.add(row));
        }

        assertThat(rows.get(0).problems()).contains(FileActionProblemCodes.CELL_NOT_COERCIBLE);
    }

    private List<RawRow> read(String content) throws IOException {
        Path file = write(content);
        List<RawRow> rows = new ArrayList<>();
        try (RowReader reader = factory.open(file, BINDING, ReadBudgets.generous())) {
            reader.forEachRow(row -> rows.add(row));
        }
        return rows;
    }

    private Path write(String content) throws IOException {
        Path file = temp.resolve("orders.csv");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
