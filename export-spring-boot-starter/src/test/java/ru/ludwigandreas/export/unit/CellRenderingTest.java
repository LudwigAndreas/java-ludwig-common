package ru.ludwigandreas.export.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.export.api.CellFormat;
import ru.ludwigandreas.export.api.CellValue;
import ru.ludwigandreas.export.api.Column;
import ru.ludwigandreas.export.api.NullPolicy;
import ru.ludwigandreas.export.api.RenderContext;
import ru.ludwigandreas.export.exception.RequiredValueMissingException;
import ru.ludwigandreas.export.format.CellTextFormatter;
import ru.ludwigandreas.export.render.DefaultCellRenderers;
import ru.ludwigandreas.export.render.RowRenderer;

/**
 * Turning a domain value into a cell, and a cell into text.
 *
 * <p>The locale and timezone cases are the ones that matter most here: they are the difference
 * between a file that is right in the synchronous path and an hour out in the deferred one, which is
 * the hardest reporting bug to reproduce because it disappears whenever anyone tries a small
 * parameter set.
 */
class CellRenderingTest {

    private record Row(String name, BigDecimal amount, LocalDate day, Instant at, Duration elapsed) {
    }

    private static final Currency EUR = Currency.getInstance("EUR");

    private static String text(CellValue value, CellFormat format, CellTextFormatter.Style style,
                               Locale locale, ZoneId zone) {
        return new CellTextFormatter(format, style, new RenderContext(locale, zone),
                EngineFixtures.MESSAGES).format(value);
    }

    @Test
    @DisplayName("a datetime is presented in the run's zone, not the server's")
    void presentsInstantsInTheRunZone() {
        Instant at = Instant.parse("2026-03-01T23:30:00Z");
        ZoneId moscow = ZoneId.of("Europe/Moscow");
        CellValue cell = DefaultCellRenderers.resolve(CellFormat.dateTime())
                .render(at, new RenderContext(Locale.ENGLISH, moscow));

        assertThat(cell).isInstanceOf(CellValue.DateTime.class);
        assertThat(((CellValue.DateTime) cell).zone()).isEqualTo(moscow);
        // 23:30 UTC is the following day in Moscow, which is the whole point of carrying the zone.
        assertThat(text(cell, CellFormat.dateTime(), CellTextFormatter.Style.CANONICAL,
                Locale.ENGLISH, moscow)).startsWith("2026-03-02T02:30");
    }

    @Test
    @DisplayName("a number is grouped for a reader and ungrouped for a parser")
    void formatsNumbersPerStyle() {
        CellValue cell = new CellValue.Number(new BigDecimal("1234567.5"));
        CellFormat format = CellFormat.number(2);

        assertThat(text(cell, format, CellTextFormatter.Style.CANONICAL, Locale.ENGLISH, ZoneId.of("UTC")))
                .isEqualTo("1234567.50");
        assertThat(text(cell, format, CellTextFormatter.Style.LOCALIZED, Locale.ENGLISH, ZoneId.of("UTC")))
                .isEqualTo("1,234,567.50");
    }

    @Test
    @DisplayName("a date follows the reader's locale, or ISO for a parser")
    void formatsDatesPerStyle() {
        CellValue cell = new CellValue.Date(LocalDate.of(2026, 3, 1));

        assertThat(text(cell, CellFormat.date(), CellTextFormatter.Style.CANONICAL, Locale.ENGLISH,
                ZoneId.of("UTC"))).isEqualTo("2026-03-01");
        assertThat(text(cell, CellFormat.date(), CellTextFormatter.Style.LOCALIZED,
                Locale.forLanguageTag("ru"), ZoneId.of("UTC"))).contains("2026");
    }

    @Test
    @DisplayName("money carries its currency code, because a text format has nowhere else to put it")
    void formatsMoneyWithItsCurrency() {
        CellValue cell = new CellValue.Money(new BigDecimal("1234.5"), EUR);

        assertThat(text(cell, CellFormat.money(EUR), CellTextFormatter.Style.CANONICAL, Locale.ENGLISH,
                ZoneId.of("UTC"))).isEqualTo("1234.50 EUR");
    }

    @Test
    @DisplayName("a percentage carries the ratio and renders as the percentage")
    void formatsPercentages() {
        CellValue cell = new CellValue.Number(new BigDecimal("0.075"));

        assertThat(text(cell, CellFormat.percent(1), CellTextFormatter.Style.CANONICAL, Locale.ENGLISH,
                ZoneId.of("UTC"))).isEqualTo("7.5%");
    }

    @Test
    @DisplayName("a duration is stored as a fraction of a day and shown as elapsed hours")
    void formatsDurations() {
        CellValue cell = DefaultCellRenderers.resolve(CellFormat.duration())
                .render(Duration.ofHours(30).plusMinutes(5).plusSeconds(6),
                        new RenderContext(Locale.ENGLISH, ZoneId.of("UTC")));

        assertThat(cell).isInstanceOf(CellValue.Number.class);
        // Thirty hours is thirty hours, not six: an elapsed time does not wrap at a day.
        assertThat(text(cell, CellFormat.duration(), CellTextFormatter.Style.CANONICAL, Locale.ENGLISH,
                ZoneId.of("UTC"))).isEqualTo("30:05:06");
    }

    @Test
    @DisplayName("a boolean uses Excel's spelling for a reader and lowercase for a parser")
    void formatsBooleans() {
        CellValue cell = new CellValue.Bool(true);

        assertThat(text(cell, CellFormat.bool(), CellTextFormatter.Style.LOCALIZED, Locale.ENGLISH,
                ZoneId.of("UTC"))).isEqualTo("TRUE");
        assertThat(text(cell, CellFormat.bool(), CellTextFormatter.Style.CANONICAL, Locale.ENGLISH,
                ZoneId.of("UTC"))).isEqualTo("true");
    }

    @Test
    @DisplayName("a degraded cell renders its localized marker, not a blank")
    void formatsDegradedCells() {
        CellValue cell = new CellValue.Error("ludwig.export.cell.unavailable");

        assertThat(text(cell, CellFormat.text(), CellTextFormatter.Style.LOCALIZED, Locale.ENGLISH,
                ZoneId.of("UTC"))).isEqualTo("n/a");
    }

    @Test
    @DisplayName("the supported type-and-format table is what the registry checks against")
    void knowsWhichPairingsWork() {
        assertThat(DefaultCellRenderers.supports(CellFormat.text(), String.class)).isTrue();
        assertThat(DefaultCellRenderers.supports(CellFormat.text(), UUID.class)).isTrue();
        assertThat(DefaultCellRenderers.supports(CellFormat.number(2), BigDecimal.class)).isTrue();
        assertThat(DefaultCellRenderers.supports(CellFormat.number(2), int.class)).isTrue();
        assertThat(DefaultCellRenderers.supports(CellFormat.date(), LocalDate.class)).isTrue();
        assertThat(DefaultCellRenderers.supports(CellFormat.money(EUR), BigDecimal.class)).isTrue();

        assertThat(DefaultCellRenderers.supports(CellFormat.money(EUR), String.class)).isFalse();
        assertThat(DefaultCellRenderers.supports(CellFormat.number(2), LocalDate.class)).isFalse();
        assertThat(DefaultCellRenderers.supports(CellFormat.date(), String.class)).isFalse();
        // A per-row currency cannot come out of a bare Number, so it needs an explicit renderer.
        assertThat(DefaultCellRenderers.supports(CellFormat.moneyPerRow(), BigDecimal.class)).isFalse();
    }

    @Test
    @DisplayName("a null value follows the column's null policy")
    void honoursTheNullPolicy() {
        Row row = new Row(null, null, null, null, null);
        RenderContext context = new RenderContext(Locale.ENGLISH, ZoneId.of("UTC"));

        List<Column<Row, ?>> columns = List.of(
                Column.<Row, String>of("empty", String.class).extractor(Row::name)
                        .headerKey("h").nullPolicy(NullPolicy.EMPTY).build(),
                Column.<Row, String>of("placeholder", String.class).extractor(Row::name)
                        .headerKey("h").nullPolicy(NullPolicy.PLACEHOLDER).build(),
                Column.<Row, BigDecimal>of("zero", BigDecimal.class).extractor(Row::amount)
                        .headerKey("h").format(CellFormat.number(2)).nullPolicy(NullPolicy.ZERO).build());

        List<CellValue> cells = new RowRenderer<>(columns, context, EngineFixtures.MESSAGES).render(row, 1);

        assertThat(cells.get(0)).isEqualTo(CellValue.Empty.INSTANCE);
        assertThat(cells.get(1)).isEqualTo(new CellValue.Text("-"));
        assertThat(cells.get(2)).isEqualTo(new CellValue.Number(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("a FAIL column with no value fails the run, naming the column and the row")
    void failsOnARequiredValue() {
        Row row = new Row(null, null, null, null, null);
        List<Column<Row, ?>> columns = List.of(
                Column.<Row, String>of("required", String.class).extractor(Row::name)
                        .headerKey("h").nullPolicy(NullPolicy.FAIL).build());
        RowRenderer<Row> renderer = new RowRenderer<>(columns,
                new RenderContext(Locale.ENGLISH, ZoneId.of("UTC")), EngineFixtures.MESSAGES);

        assertThatThrownBy(() -> renderer.render(row, 7))
                .isInstanceOf(RequiredValueMissingException.class)
                .hasMessageContaining("required");
    }

    @Test
    @DisplayName("an enum renders as its constant name, not its toString")
    void rendersEnumsByName() {
        CellValue cell = DefaultCellRenderers.resolve(CellFormat.text())
                .render(Style.LOUD, new RenderContext(Locale.ENGLISH, ZoneId.of("UTC")));

        assertThat(cell).isEqualTo(new CellValue.Text("LOUD"));
    }

    /** An enum whose toString is overridden, which is exactly the case the rule protects against. */
    private enum Style {
        LOUD;

        @Override
        public String toString() {
            return "shouty";
        }
    }
}
