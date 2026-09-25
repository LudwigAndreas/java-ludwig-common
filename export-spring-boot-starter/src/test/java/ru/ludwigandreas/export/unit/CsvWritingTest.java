package ru.ludwigandreas.export.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.ludwigandreas.export.api.Aggregate;
import ru.ludwigandreas.export.api.CellFormat;
import ru.ludwigandreas.export.api.CellValue;
import ru.ludwigandreas.export.api.ColumnSpec;
import ru.ludwigandreas.export.api.MultiSheetStrategy;
import ru.ludwigandreas.export.api.RenderContext;
import ru.ludwigandreas.export.api.RenderedRow;
import ru.ludwigandreas.export.api.ReportWriter;
import ru.ludwigandreas.export.api.SheetSpec;
import ru.ludwigandreas.export.api.StandardReportFormats;
import ru.ludwigandreas.export.api.WriterContext;
import ru.ludwigandreas.export.exception.InvalidFormatOptionException;
import ru.ludwigandreas.export.format.CellSanitizer;
import ru.ludwigandreas.export.format.csv.CsvProfile;
import ru.ludwigandreas.export.format.csv.CsvReportWriter;

/**
 * What the CSV writer puts on disk, byte for byte.
 *
 * <p>Driven through the writer rather than through the engine, because these are questions about one
 * format's output and running the whole pipeline to ask them would make a quoting failure look like
 * an engine failure.
 */
class CsvWritingTest {

    @TempDir
    Path tempDirectory;

    private static final ColumnSpec TEXT_COLUMN =
            new ColumnSpec("note", "Note", CellFormat.text(), 30, Aggregate.NONE, false);

    private String write(CsvProfile profile, Locale locale, List<CellValue> cells) throws IOException {
        Path target = tempDirectory.resolve(UUID.randomUUID() + ".csv");
        SheetSpec sheet = SheetSpec.single("data", "Data", List.of(TEXT_COLUMN));
        WriterContext context = new WriterContext(UUID.randomUUID(), StandardReportFormats.CSV, target,
                List.of(sheet), MultiSheetStrategy.REJECT, new RenderContext(locale, ZoneId.of("UTC")),
                List.of(), Map.of(), false, null);
        try (ReportWriter writer = new CsvReportWriter(context, profile, EngineFixtures.MESSAGES)) {
            writer.beginSheet(sheet);
            for (CellValue cell : cells) {
                writer.writeRow(RenderedRow.of(List.of(cell)));
            }
            writer.endSheet(List.of());
            writer.finish();
        }
        return Files.readString(target, profile.charset());
    }

    private String dataLineOf(String written) {
        String[] lines = written.split("\r\n", -1);
        return lines[1];
    }

    private static CsvProfile rfc4180() {
        return CsvProfile.baseProfile(CsvProfile.RFC_4180, Locale.ENGLISH);
    }

    @ParameterizedTest
    @MethodSource("quotingMatrix")
    @DisplayName("a field is quoted exactly when the standard requires it")
    void quotesWhatItHasTo(String value, String expected) throws IOException {
        String written = write(rfc4180(), Locale.ENGLISH, List.of(new CellValue.Text(value)));

        assertThat(dataLineOf(written)).isEqualTo(expected);
    }

    static Stream<Arguments> quotingMatrix() {
        return Stream.of(
                Arguments.of("plain text", "plain text"),
                Arguments.of("has,a,comma", "\"has,a,comma\""),
                Arguments.of("has \"quotes\"", "\"has \"\"quotes\"\"\""),
                Arguments.of("semicolon;inside", "semicolon;inside"),
                Arguments.of("", ""));
    }

    @Test
    @DisplayName("a value with an embedded newline is quoted, so the row count stays right")
    void quotesEmbeddedNewlines() throws IOException {
        String written = write(rfc4180(), Locale.ENGLISH,
                List.of(new CellValue.Text("first\r\nsecond")));

        assertThat(written).contains("\"first\r\nsecond\"");
        // Three physical lines, two logical records: header, the quoted value, the terminator.
        assertThat(written.split("\r\n", -1)).hasSize(4);
    }

    @Test
    @DisplayName("padding is preserved by quoting it, rather than trimmed away")
    void preservesDeliberatePadding() throws IOException {
        String written = write(rfc4180(), Locale.ENGLISH, List.of(new CellValue.Text("  00123  ")));

        assertThat(dataLineOf(written)).isEqualTo("\"  00123  \"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+1", "-2+3+cmd|' /C calc'!A0", "@SUM(A1)", "\tlead", "\rlead"})
    @DisplayName("a text cell that a spreadsheet would execute is neutralised")
    void neutralisesFormulas(String value) throws IOException {
        String written = write(rfc4180(), Locale.ENGLISH, List.of(new CellValue.Text(value)));

        String field = dataLineOf(written);
        String unquoted = field.startsWith("\"") ? field.substring(1, field.length() - 1) : field;
        assertThat(unquoted).startsWith(String.valueOf(CellSanitizer.TEXT_MARKER));
        assertThat(CellSanitizer.sanitize(value)).isEqualTo(CellSanitizer.TEXT_MARKER + value);
    }

    @Test
    @DisplayName("a negative number is not neutralised, because it is not a text cell")
    void leavesNumbersAlone() throws IOException {
        String written = write(rfc4180(), Locale.ENGLISH,
                List.of(new CellValue.Number(new BigDecimal("-5"))));

        // The column declares TEXT, so the value renders through the text formatter - and is still
        // not sanitised, because sanitisation follows the cell's shape rather than the column's.
        assertThat(dataLineOf(written)).doesNotStartWith(String.valueOf(CellSanitizer.TEXT_MARKER));
    }

    @Test
    @DisplayName("the excel profile writes a byte-order mark and the rfc4180 profile does not")
    void writesTheByteOrderMarkOnlyForExcel() throws IOException {
        String excel = write(CsvProfile.baseProfile(CsvProfile.EXCEL, Locale.ENGLISH), Locale.ENGLISH,
                List.of(new CellValue.Text("x")));
        String canonical = write(rfc4180(), Locale.ENGLISH, List.of(new CellValue.Text("x")));

        assertThat(excel).startsWith("\uFEFF");
        assertThat(canonical).doesNotStartWith("\uFEFF");
        assertThat(excel.getBytes(StandardCharsets.UTF_8)[0]).isEqualTo((byte) 0xEF);
    }

    @Test
    @DisplayName("the excel delimiter follows the locale's list separator, not the format's name")
    void takesTheDelimiterFromTheLocale() {
        assertThat(CsvProfile.baseProfile(CsvProfile.EXCEL, Locale.ENGLISH).delimiter()).isEqualTo(',');
        assertThat(CsvProfile.baseProfile(CsvProfile.EXCEL, Locale.forLanguageTag("ru")).delimiter())
                .isEqualTo(';');
        assertThat(CsvProfile.baseProfile(CsvProfile.EXCEL, Locale.GERMANY).delimiter()).isEqualTo(';');
        // rfc4180 is a comma everywhere, by definition rather than by locale.
        assertThat(CsvProfile.baseProfile(CsvProfile.RFC_4180, Locale.forLanguageTag("ru")).delimiter())
                .isEqualTo(',');
    }

    @ParameterizedTest
    @MethodSource("unusableOptions")
    @DisplayName("an option value the writer cannot use is rejected, not ignored")
    void rejectsUnusableOptions(String option, String value) {
        assertThatThrownBy(() -> CsvProfile.resolve(Map.of(option, value), CsvProfile.EXCEL, true,
                Locale.ENGLISH))
                .isInstanceOf(InvalidFormatOptionException.class)
                .hasMessageContaining(option);
    }

    static Stream<Arguments> unusableOptions() {
        return Stream.of(
                Arguments.of("profile", "tsv"),
                Arguments.of("delimiter", ";;"),
                Arguments.of("delimiter", "\""),
                Arguments.of("charset", "UTF8X"),
                Arguments.of("bom", "maybe"));
    }

    @Test
    @DisplayName("a request cannot override the profile when the estate forbids it")
    void honoursTheOverrideSwitch() {
        assertThatThrownBy(() -> CsvProfile.resolve(Map.of("profile", CsvProfile.RFC_4180),
                CsvProfile.EXCEL, false, Locale.ENGLISH))
                .isInstanceOf(InvalidFormatOptionException.class);
    }

    @Test
    @DisplayName("the whole file is exact, byte for byte, for a small fixture")
    void writesAnExactFile() throws IOException {
        Path target = tempDirectory.resolve("exact.csv");
        List<ColumnSpec> columns = List.of(
                new ColumnSpec("name", "Name", CellFormat.text(), 20, Aggregate.NONE, false),
                new ColumnSpec("due", "Due", CellFormat.date(), 12, Aggregate.NONE, false));
        SheetSpec sheet = SheetSpec.single("data", "Data", columns);
        WriterContext context = new WriterContext(UUID.randomUUID(), StandardReportFormats.CSV, target,
                List.of(sheet), MultiSheetStrategy.REJECT,
                new RenderContext(Locale.ENGLISH, ZoneId.of("UTC")), List.of(), Map.of(), false, null);

        try (ReportWriter writer = new CsvReportWriter(context, rfc4180(), EngineFixtures.MESSAGES)) {
            writer.beginSheet(sheet);
            writer.writeRow(RenderedRow.of(List.of(new CellValue.Text("Acme, Inc."),
                    new CellValue.Date(LocalDate.of(2026, 3, 1)))));
            writer.writeRow(RenderedRow.of(List.of(CellValue.Empty.INSTANCE,
                    CellValue.Empty.INSTANCE)));
            writer.endSheet(List.of());
            writer.finish();
        }

        assertThat(Files.readAllBytes(target)).isEqualTo(
                "Name,Due\r\n\"Acme, Inc.\",2026-03-01\r\n,\r\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the state machine refuses a row before its sheet is open")
    void enforcesTheStateMachine() throws IOException {
        Path target = tempDirectory.resolve("state.csv");
        SheetSpec sheet = SheetSpec.single("data", "Data", List.of(TEXT_COLUMN));
        WriterContext context = new WriterContext(UUID.randomUUID(), StandardReportFormats.CSV, target,
                List.of(sheet), MultiSheetStrategy.REJECT,
                new RenderContext(Locale.ENGLISH, ZoneId.of("UTC")), List.of(), Map.of(), false, null);

        try (ReportWriter writer = new CsvReportWriter(context, rfc4180(), EngineFixtures.MESSAGES)) {
            assertThatThrownBy(() -> writer.writeRow(RenderedRow.of(List.of(CellValue.Empty.INSTANCE))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no sheet open");

            writer.beginSheet(sheet);
            assertThatThrownBy(() -> writer.writeRow(RenderedRow.of(
                    List.of(CellValue.Empty.INSTANCE, CellValue.Empty.INSTANCE))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("2 cells");
        }
    }
}
