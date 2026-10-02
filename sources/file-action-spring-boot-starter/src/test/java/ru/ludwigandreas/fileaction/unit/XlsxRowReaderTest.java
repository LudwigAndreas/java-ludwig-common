package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
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
import ru.ludwigandreas.fileaction.format.xlsx.read.XlsxRowReaderFactory;

/** Reading a workbook through the SAX path: headers, cells, raw numbers, sheets and the ceilings. */
class XlsxRowReaderTest {

    @TempDir
    Path temp;

    private final XlsxRowReaderFactory factory = new XlsxRowReaderFactory();

    private static final RowBinding<OrderLine> BINDING = RowBinding.of(OrderLine.class)
            .sheet("Orders")
            .column("sku", "SKU").aliases("Article")
            .column("quantity", "Qty")
            .column("price", "Price").optional()
            .column("dueDate", "Due").optional()
            .column("comment", "Comment").optional()
            .build();

    @Test
    @DisplayName("headers are available as soon as the reader is open, before any data row is read")
    void readsHeadersEagerly() throws IOException {
        Path file = workbook(List.of("SKU", "Qty"), List.of(List.of("A-1", 3)));

        try (RowReader reader = factory.open(file, BINDING, ReadBudgets.generous())) {
            assertThat(reader.headers()).containsExactly("SKU", "Qty");
            assertThat(reader.sheet()).isEqualTo("Orders");
        }
    }

    @Test
    @DisplayName("data rows read, with their displayed row numbers")
    void readsRows() throws IOException {
        List<RawRow> rows = read(List.of("SKU", "Qty"),
                List.of(List.of("A-1", 3), List.of("A-2", 5)));

        assertThat(rows).extracting(RawRow::displayedRow).containsExactly(2, 3);
        assertThat(rows.get(1).cell("SKU").trimmedText()).isEqualTo("A-2");
    }

    @Test
    @DisplayName("a numeric cell arrives as a number, not as a formatted string")
    void numbersArriveAsNumbers() throws IOException {
        List<RawRow> rows = read(List.of("SKU", "Qty", "Price"),
                List.of(List.of("A-1", 3, 1234.56d)));

        assertThat(rows.get(0).cell("Price").number())
                .as("formatting it and parsing it back is what loses the grouping separator and the"
                        + " decimal separator to the locale")
                .isNotNull();
        assertThat(rows.get(0).cell("Price").number().doubleValue()).isEqualTo(1234.56d);
        assertThat(rows.get(0).cell("Price").dateLike()).isFalse();
    }

    @Test
    @DisplayName("a date-formatted cell arrives as a number flagged date-like, so no string is parsed")
    void datesArriveAsFlaggedNumbers() throws IOException {
        Path file = temp.resolve("orders.xlsx");
        Workbooks.writeWithDateFormat(file, "Orders", List.of("SKU", "Qty", "Due"),
                List.of(List.of("A-1", 3, LocalDate.of(2026, 2, 1))), "dd.mm.yyyy");

        List<RawRow> rows = readFrom(file);

        assertThat(rows.get(0).cell("Due").dateLike())
                .as("the date-ness of the display format is the only thing in OOXML that separates a date"
                        + " from a plain number")
                .isTrue();
        assertThat(rows.get(0).cell("Due").number()).isNotNull();
    }

    @Test
    @DisplayName("an undeclared column is ignored and an inserted column shifts nothing")
    void bindsByHeaderName() throws IOException {
        List<RawRow> rows = read(List.of("SKU", "Inserted", "Qty"),
                List.of(List.of("A-1", "x", 3)));

        assertThat(rows.get(0).cells()).containsOnlyKeys("SKU", "Qty");
        assertThat(rows.get(0).cell("Qty").number().intValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("an alias binds to the declared column")
    void bindsAliases() throws IOException {
        List<RawRow> rows = read(List.of("Article", "Qty"), List.of(List.of("A-1", 3)));

        assertThat(rows.get(0).cell("SKU").trimmedText()).isEqualTo("A-1");
    }

    @Test
    @DisplayName("an empty cell is absent rather than present-and-blank")
    void emptyCellsAreAbsent() throws IOException {
        List<RawRow> rows = read(List.of("SKU", "Qty", "Comment"),
                List.of(List.of("A-1", 3)));

        assertThat(rows.get(0).cells()).doesNotContainKey("Comment");
        assertThat(rows.get(0).cell("Comment").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a wholly blank row is skipped, because spreadsheets are full of them")
    void skipsBlankRows() throws IOException {
        List<RawRow> rows = read(List.of("SKU", "Qty"),
                List.of(List.of("A-1", 3), List.of(), List.of("A-2", 5)));

        assertThat(rows).hasSize(2);
    }

    @Test
    @DisplayName("a binding naming a sheet that is not there is refused, rather than reading the wrong one")
    void refusesAMissingSheet() throws IOException {
        Path file = temp.resolve("orders.xlsx");
        Workbooks.write(file, "Something Else", List.of("SKU", "Qty"), List.of(List.of("A-1", 3)));

        assertThatThrownBy(() -> factory.open(file, BINDING, ReadBudgets.generous()))
                .isInstanceOf(FileRejectedException.class)
                .hasFieldOrPropertyWithValue("code", FileActionProblemCodes.SHEET_NOT_FOUND);
    }

    @Test
    @DisplayName("a binding naming no sheet reads the first one")
    void defaultsToTheFirstSheet() throws IOException {
        RowBinding<OrderLine> anySheet = RowBinding.of(OrderLine.class)
                .column("sku", "SKU")
                .column("quantity", "Qty")
                .build();
        Path file = temp.resolve("orders.xlsx");
        Workbooks.write(file, "Whatever", List.of("SKU", "Qty"), List.of(List.of("A-1", 3)));

        try (RowReader reader = factory.open(file, anySheet, ReadBudgets.generous())) {
            assertThat(reader.sheet()).isEqualTo("Whatever");
        }
    }

    @Test
    @DisplayName("the row ceiling stops the read and refuses the submission")
    void enforcesTheRowCeiling() throws IOException {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            rows.add(List.of("A-" + i, 1));
        }
        Path file = workbook(List.of("SKU", "Qty"), rows);

        assertThatThrownBy(() -> {
            try (RowReader reader = factory.open(file, BINDING, ReadBudgets.withMaxRows(5))) {
                reader.forEachRow(row -> true);
            }
        })
                .isInstanceOf(FileRejectedException.class)
                .hasFieldOrPropertyWithValue("code", FileActionProblemCodes.TOO_MANY_ROWS);
    }

    @Test
    @DisplayName("a visitor that returns false stops the parse without reading the rest of the sheet")
    void visitorCanStop() throws IOException {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rows.add(List.of("A-" + i, 1));
        }
        Path file = workbook(List.of("SKU", "Qty"), rows);
        List<RawRow> seen = new ArrayList<>();

        try (RowReader reader = factory.open(file, BINDING, ReadBudgets.generous())) {
            reader.forEachRow(row -> {
                seen.add(row);
                return seen.size() < 3;
            });
        }

        assertThat(seen).hasSize(3);
    }

    @Test
    @DisplayName("a workbook with no entries at all is refused")
    void refusesAnEmptyContainer() throws IOException {
        Path file = temp.resolve("empty.xlsx");
        java.nio.file.Files.write(file, new byte[] {0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0,
                                                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0});

        assertThatThrownBy(() -> factory.open(file, BINDING, ReadBudgets.generous()))
                .isInstanceOf(FileRejectedException.class);
    }

    private List<RawRow> read(List<String> headers, List<List<Object>> rows) throws IOException {
        return readFrom(workbook(headers, rows));
    }

    private List<RawRow> readFrom(Path file) throws IOException {
        List<RawRow> collected = new ArrayList<>();
        try (RowReader reader = factory.open(file, BINDING, ReadBudgets.generous())) {
            reader.forEachRow(row -> collected.add(row));
        }
        return collected;
    }

    private Path workbook(List<String> headers, List<List<Object>> rows) throws IOException {
        Path file = temp.resolve("orders.xlsx");
        Workbooks.write(file, "Orders", headers, rows);
        return file;
    }
}
