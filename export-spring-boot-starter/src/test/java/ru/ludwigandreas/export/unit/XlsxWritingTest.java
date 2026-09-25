package ru.ludwigandreas.export.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ludwigandreas.export.api.Aggregate;
import ru.ludwigandreas.export.api.CellFormat;
import ru.ludwigandreas.export.api.CellValue;
import ru.ludwigandreas.export.api.ColumnSpec;
import ru.ludwigandreas.export.api.FormatCapabilities;
import ru.ludwigandreas.export.api.MetadataEntry;
import ru.ludwigandreas.export.api.MultiSheetStrategy;
import ru.ludwigandreas.export.api.RenderContext;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.RenderedRow;
import ru.ludwigandreas.export.api.SheetSpec;
import ru.ludwigandreas.export.api.StandardReportFormats;
import ru.ludwigandreas.export.api.WriterContext;
import ru.ludwigandreas.export.format.CellSanitizer;
import ru.ludwigandreas.export.format.xlsx.DirectoryXlsxTemplateSource;
import ru.ludwigandreas.export.format.xlsx.SheetNames;
import ru.ludwigandreas.export.format.xlsx.XlsxReportWriter;
import ru.ludwigandreas.export.format.xlsx.XlsxTemplateSource;

/**
 * What the XLSX writer actually puts in the workbook, read back with POI.
 *
 * <p>Asserting cell <em>types</em> rather than rendered strings is the whole point: a date written
 * as text and a date written as a date look identical in a screenshot and behave completely
 * differently in the hands of the person who received the file. A test that compared displayed text
 * would pass for both.
 */
class XlsxWritingTest {

    @TempDir
    Path tempDirectory;

    private static final Currency EUR = Currency.getInstance("EUR");

    private static final List<ColumnSpec> COLUMNS = List.of(
            new ColumnSpec("name", "Name", CellFormat.text(), 20, Aggregate.NONE, false),
            new ColumnSpec("amount", "Amount", CellFormat.money(EUR), 14, Aggregate.SUM, false),
            new ColumnSpec("day", "Day", CellFormat.date(), 12, Aggregate.MAX, false),
            new ColumnSpec("at", "At", CellFormat.dateTime(), 20, Aggregate.NONE, false),
            new ColumnSpec("done", "Done", CellFormat.bool(), 8, Aggregate.NONE, false),
            new ColumnSpec("took", "Took", CellFormat.duration(), 12, Aggregate.NONE, false));

    private Path write(List<SheetSpec> sheets, List<MetadataEntry> metadata, boolean totals,
                       WriterBody body) throws IOException {
        Path target = tempDirectory.resolve(UUID.randomUUID() + ".xlsx");
        WriterContext context = new WriterContext(UUID.randomUUID(), StandardReportFormats.XLSX,
                target, sheets, MultiSheetStrategy.REJECT,
                new RenderContext(Locale.ENGLISH, ZoneId.of("UTC")), metadata, Map.of(), totals, null);
        try (XlsxReportWriter writer = new XlsxReportWriter(context, 100, true, !metadata.isEmpty(),
                Optional.empty(), EngineFixtures.MESSAGES)) {
            body.accept(writer);
            writer.finish();
        }
        return target;
    }

    private static SheetSpec sheet() {
        return SheetSpec.single("data", "Data", COLUMNS);
    }

    private static RenderedRow sampleRow() {
        return RenderedRow.of(List.of(
                new CellValue.Text("Acme"),
                new CellValue.Money(new BigDecimal("1234.50"), EUR),
                new CellValue.Date(LocalDate.of(2026, 3, 1)),
                new CellValue.DateTime(Instant.parse("2026-03-01T12:30:00Z"), ZoneId.of("UTC")),
                new CellValue.Bool(true),
                new CellValue.Number(BigDecimal.valueOf(Duration.ofHours(2).toMillis())
                        .movePointLeft(3).divide(BigDecimal.valueOf(86400L), 12,
                                java.math.RoundingMode.HALF_UP))));
    }

    @Test
    @DisplayName("every cell is written as its own type, not as text")
    void writesTypedCells() throws IOException {
        Path file = write(List.of(sheet()), List.of(), false, writer -> {
            writer.beginSheet(sheet());
            writer.writeRow(sampleRow());
            writer.endSheet(List.of());
        });

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(file))) {
            org.apache.poi.ss.usermodel.Row row = workbook.getSheetAt(0).getRow(1);

            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(1234.50);
            assertThat(row.getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(DateUtil.isCellDateFormatted(row.getCell(2))).isTrue();
            assertThat(row.getCell(2).getLocalDateTimeCellValue().toLocalDate())
                    .isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(DateUtil.isCellDateFormatted(row.getCell(3))).isTrue();
            assertThat(row.getCell(4).getCellType()).isEqualTo(CellType.BOOLEAN);
            assertThat(row.getCell(4).getBooleanCellValue()).isTrue();
            assertThat(row.getCell(5).getCellType()).isEqualTo(CellType.NUMERIC);
        }
    }

    @Test
    @DisplayName("the money column carries a currency number format, not a currency in the text")
    void formatsMoneyWithANumberFormat() throws IOException {
        Path file = write(List.of(sheet()), List.of(), false, writer -> {
            writer.beginSheet(sheet());
            writer.writeRow(sampleRow());
            writer.endSheet(List.of());
        });

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(file))) {
            Cell amount = workbook.getSheetAt(0).getRow(1).getCell(1);

            assertThat(amount.getCellStyle().getDataFormatString()).contains("EUR");
            // The value itself is a plain number, so SUM over the column works.
            assertThat(amount.getNumericCellValue()).isEqualTo(1234.50);
        }
    }

    @Test
    @DisplayName("a duration carries Excel's elapsed-time format, which does not wrap at 24 hours")
    void formatsDurationsAsElapsedTime() throws IOException {
        Path file = write(List.of(sheet()), List.of(), false, writer -> {
            writer.beginSheet(sheet());
            writer.writeRow(sampleRow());
            writer.endSheet(List.of());
        });

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(file))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(5).getCellStyle()
                    .getDataFormatString()).isEqualTo("[h]:mm:ss");
        }
    }

    @Test
    @DisplayName("the header row is frozen and carries an autofilter over the columns")
    void freezesTheHeaderAndFilters() throws IOException {
        Path file = write(List.of(sheet()), List.of(), false, writer -> {
            writer.beginSheet(sheet());
            writer.writeRow(sampleRow());
            writer.endSheet(List.of());
        });

        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(file))) {
            Sheet data = workbook.getSheetAt(0);

            assertThat(data.getPaneInformation().isFreezePane()).isTrue();
            assertThat(data.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);
            assertThat(((org.apache.poi.xssf.usermodel.XSSFSheet) data).getCTWorksheet().isSetAutoFilter())
                    .isTrue();
            assertThat(data.getColumnWidth(0)).isEqualTo(20 * 256);
        }
    }

    @Test
    @DisplayName("the style cache creates one style per look, not one per cell")
    void reusesCellStyles() throws IOException {
        Path file = write(List.of(sheet()), List.of(), false, writer -> {
            writer.beginSheet(sheet());
            for (int i = 0; i < 2_000; i++) {
                writer.writeRow(sampleRow());
            }
            writer.endSheet(List.of());
        });

        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(file))) {
            // Twelve thousand cells, six columns, two looks in use (header and normal). POI's
            // ceiling is around 64,000 styles; an exporter that created one per cell would have
            // thrown thousands of rows ago.
            assertThat(workbook.getNumCellStyles()).isLessThan(30);
        }
    }

    @Test
    @DisplayName("a per-row currency costs one style per currency, and no more")
    void addsOneStylePerCurrency() throws IOException {
        List<ColumnSpec> perRow = List.of(
                new ColumnSpec("amount", "Amount", CellFormat.moneyPerRow(), 14, Aggregate.NONE, false));
        SheetSpec spec = SheetSpec.single("data", "Data", perRow);
        List<String> codes = List.of("EUR", "USD", "GBP");

        Path file = write(List.of(spec), List.of(), false, writer -> {
            writer.beginSheet(spec);
            for (int i = 0; i < 300; i++) {
                writer.writeRow(RenderedRow.of(List.of(new CellValue.Money(BigDecimal.ONE,
                        Currency.getInstance(codes.get(i % codes.size()))))));
            }
            writer.endSheet(List.of());
        });

        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(file))) {
            assertThat(workbook.getNumCellStyles()).isLessThan(15);
            List<String> formats = new ArrayList<>();
            Sheet data = workbook.getSheetAt(0);
            for (int i = 1; i <= codes.size(); i++) {
                formats.add(data.getRow(i).getCell(0).getCellStyle().getDataFormatString());
            }
            assertThat(formats).anyMatch(f -> f.contains("USD"));
            assertThat(formats).anyMatch(f -> f.contains("GBP"));
        }
    }

    @Test
    @DisplayName("the totals row is written last, bordered, with its column's number format")
    void writesATotalsRow() throws IOException {
        Path file = write(List.of(sheet()), List.of(), true, writer -> {
            writer.beginSheet(sheet());
            writer.writeRow(sampleRow());
            writer.endSheet(List.of(CellValue.Empty.INSTANCE,
                    new CellValue.Money(new BigDecimal("1234.50"), EUR),
                    new CellValue.Date(LocalDate.of(2026, 3, 1)),
                    CellValue.Empty.INSTANCE, CellValue.Empty.INSTANCE, CellValue.Empty.INSTANCE));
        });

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(file))) {
            org.apache.poi.ss.usermodel.Row totals = workbook.getSheetAt(0).getRow(2);

            assertThat(totals.getCell(0).getStringCellValue()).isEqualTo("Total");
            assertThat(totals.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(totals.getCell(1).getCellStyle().getDataFormatString()).contains("EUR");
            assertThat(totals.getCell(1).getCellStyle().getBorderTop())
                    .isEqualTo(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        }
    }

    @Test
    @DisplayName("the metadata sheet is written last and carries the run's provenance")
    void writesTheMetadataSheet() throws IOException {
        List<MetadataEntry> metadata = List.of(
                new MetadataEntry("Report", "Sales"),
                new MetadataEntry("Requested by", "tester"),
                new MetadataEntry("Rows", "1"));

        Path file = write(List.of(sheet()), metadata, false, writer -> {
            writer.beginSheet(sheet());
            writer.writeRow(sampleRow());
            writer.endSheet(List.of());
        });

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(file))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            Sheet meta = workbook.getSheetAt(1);
            assertThat(meta.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Report");
            assertThat(meta.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Sales");
            assertThat(meta.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Rows");
        }
    }

    @Test
    @DisplayName("late metadata is appended after the plan's, so the sheet reads ask-then-outcome")
    void appendsLateMetadata() throws IOException {
        // The facts that only exist once the run is over. The engine adds them just before finish, and
        // the assertion that matters is that they land *after* the plan's entries rather than merged
        // into them: the sheet is meant to read as what was asked for, then what happened.
        List<MetadataEntry> planned = List.of(new MetadataEntry("Report", "Sales"));

        Path file = write(List.of(sheet()), planned, false, writer -> {
            writer.beginSheet(sheet());
            writer.writeRow(sampleRow());
            writer.endSheet(List.of());
            writer.addMetadata(List.of(
                    new MetadataEntry("Rows", "1"),
                    new MetadataEntry("Incomplete data from", "none")));
        });

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(file))) {
            Sheet meta = workbook.getSheetAt(1);
            assertThat(meta.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Report");
            assertThat(meta.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Rows");
            assertThat(meta.getRow(1).getCell(1).getStringCellValue()).isEqualTo("1");
            // Written even though nothing degraded: an absent row cannot be told apart from a writer
            // that forgot one, and this sheet exists so a reader can tell the difference.
            assertThat(meta.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Incomplete data from");
            assertThat(meta.getRow(2).getCell(1).getStringCellValue()).isEqualTo("none");
        }
    }

    @Test
    @DisplayName("a text cell that a spreadsheet would execute is neutralised here too, not only in CSV")
    void neutralisesFormulasInXlsx() throws IOException {
        Path file = write(List.of(sheet()), List.of(), false, writer -> {
            writer.beginSheet(sheet());
            writer.writeRow(RenderedRow.of(List.of(
                    new CellValue.Text("=HYPERLINK(\"https://example.test\")"),
                    CellValue.Empty.INSTANCE, CellValue.Empty.INSTANCE, CellValue.Empty.INSTANCE,
                    CellValue.Empty.INSTANCE, CellValue.Empty.INSTANCE)));
            writer.endSheet(List.of());
        });

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(file))) {
            Cell cell = workbook.getSheetAt(0).getRow(1).getCell(0);

            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue())
                    .startsWith(String.valueOf(CellSanitizer.TEXT_MARKER));
        }
    }

    @Test
    @DisplayName("an empty cell is blank, not the two characters that look like it")
    void writesGenuinelyEmptyCells() throws IOException {
        Path file = write(List.of(sheet()), List.of(), false, writer -> {
            writer.beginSheet(sheet());
            writer.writeRow(RenderedRow.of(List.of(CellValue.Empty.INSTANCE, CellValue.Empty.INSTANCE,
                    CellValue.Empty.INSTANCE, CellValue.Empty.INSTANCE, CellValue.Empty.INSTANCE,
                    CellValue.Empty.INSTANCE)));
            writer.endSheet(List.of());
        });

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(file))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getCellType())
                    .isEqualTo(CellType.BLANK);
        }
    }

    @Test
    @DisplayName("sheet names are sanitised, truncated and deduplicated to what a workbook accepts")
    void sanitisesSheetNames() {
        List<String> taken = new ArrayList<>();

        String first = SheetNames.of("Sales / Q1 [2026]: *draft?*", taken);
        taken.add(first);
        String second = SheetNames.of("Sales / Q1 [2026]: *draft?*", taken);

        assertThat(first).isEqualTo("Sales  Q1 2026 draft");
        assertThat(second).isEqualTo("Sales  Q1 2026 draft (2)");
        assertThat(SheetNames.of("x".repeat(60), List.of())).hasSize(SheetNames.MAX_LENGTH);
        assertThat(SheetNames.of("[]:*?/\\", List.of())).isEqualTo("Sheet");
        // Excel compares names case-insensitively, so this has to collide.
        assertThat(SheetNames.of("DATA", List.of("data"))).isEqualTo("DATA (2)");
    }

    @Test
    @DisplayName("a continuation sheet is named predictably, inside the length limit")
    void namesContinuationSheets() {
        assertThat(SheetNames.continuation("Orders", 2, List.of())).isEqualTo("Orders (2)");
        assertThat(SheetNames.continuation("x".repeat(40), 3, List.of()))
                .hasSize(SheetNames.MAX_LENGTH)
                .endsWith(" (3)");
    }

    @Test
    @DisplayName("a sheet that reaches the format's row ceiling rolls over onto a continuation")
    void rollsOverAtTheSheetCeiling() throws IOException {
        // A format identical to XLSX except that one sheet holds five rows. Driving the rollover
        // through the capability rather than through a million rows is what makes this a test
        // somebody will actually run - and it exercises the same code path production does, because
        // the writer reads the ceiling from the format rather than from a constant.
        ReportFormat tiny = new ReportFormat() {
            @Override
            public String id() {
                return "xlsx-tiny";
            }

            @Override
            public String mediaType() {
                return StandardReportFormats.XLSX.mediaType();
            }

            @Override
            public String fileExtension() {
                return "xlsx";
            }

            @Override
            public FormatCapabilities capabilities() {
                return FormatCapabilities.spreadsheet(5);
            }
        };
        Path target = tempDirectory.resolve("rollover.xlsx");
        SheetSpec spec = SheetSpec.single("data", "Orders", COLUMNS);
        WriterContext context = new WriterContext(UUID.randomUUID(), tiny, target, List.of(spec),
                MultiSheetStrategy.REJECT, new RenderContext(Locale.ENGLISH, ZoneId.of("UTC")),
                List.of(), Map.of(), false, null);

        try (XlsxReportWriter writer = new XlsxReportWriter(context, 100, true, false,
                Optional.empty(), EngineFixtures.MESSAGES)) {
            writer.beginSheet(spec);
            for (int i = 0; i < 12; i++) {
                writer.writeRow(sampleRow());
            }
            writer.endSheet(List.of());
            writer.finish();
        }

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(target))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(3);
            assertThat(workbook.getSheetName(0)).isEqualTo("Orders");
            assertThat(workbook.getSheetName(1)).isEqualTo("Orders continued (2)");
            assertThat(workbook.getSheetName(2)).isEqualTo("Orders continued (3)");
            // Every continuation repeats the header, because a sheet of unlabelled columns is
            // unusable on its own and a spreadsheet cannot say "see the previous tab".
            assertThat(workbook.getSheetAt(1).getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("Name");
            // 12 rows across three sheets of (5 - 1 header) = 4, 4 and 4.
            assertThat(workbook.getSheetAt(2).getLastRowNum()).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("a branding template becomes the workbook the report is written into")
    void writesIntoATemplate() throws IOException {
        Path templateFile = tempDirectory.resolve("branding.xlsx");
        try (XSSFWorkbook template = new XSSFWorkbook()) {
            Sheet cover = template.createSheet("Cover");
            cover.createRow(0).createCell(0).setCellValue("ACME Corporation");
            try (var out = Files.newOutputStream(templateFile)) {
                template.write(out);
            }
        }

        Path target = tempDirectory.resolve("branded.xlsx");
        SheetSpec spec = SheetSpec.single("data", "Data", COLUMNS);
        WriterContext context = new WriterContext(UUID.randomUUID(), StandardReportFormats.XLSX,
                target, List.of(spec), MultiSheetStrategy.REJECT,
                new RenderContext(Locale.ENGLISH, ZoneId.of("UTC")), List.of(), Map.of(), false,
                "branding");
        XlsxTemplateSource source = new DirectoryXlsxTemplateSource(tempDirectory);

        try (XlsxReportWriter writer = new XlsxReportWriter(context, 100, true, false,
                source.open("branding"), EngineFixtures.MESSAGES)) {
            writer.beginSheet(spec);
            writer.writeRow(sampleRow());
            writer.endSheet(List.of());
            writer.finish();
        }

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(target))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheet("Cover").getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("ACME Corporation");
            assertThat(workbook.getSheet("Data").getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo("Acme");
        }
    }

    @Test
    @DisplayName("a template name that escapes the directory, or does not exist, yields no template")
    void refusesTemplatesOutsideTheDirectory() throws IOException {
        XlsxTemplateSource source = new DirectoryXlsxTemplateSource(tempDirectory);

        assertThat(source.open("../secrets")).isEmpty();
        assertThat(source.open("nothing-here")).isEmpty();
        assertThat(source.open(null)).isEmpty();
    }

    /** One body of writer calls, so each test says only what it is about. */
    @FunctionalInterface
    private interface WriterBody {

        void accept(XlsxReportWriter writer) throws IOException;
    }
}
