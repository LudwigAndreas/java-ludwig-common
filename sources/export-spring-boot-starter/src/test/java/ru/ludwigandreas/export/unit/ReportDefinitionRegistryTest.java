package ru.ludwigandreas.export.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.export.api.CellFormat;
import ru.ludwigandreas.export.api.Column;
import ru.ludwigandreas.export.api.Enricher;
import ru.ludwigandreas.export.api.EnrichmentStage;
import ru.ludwigandreas.export.api.MultiSheetStrategy;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.api.ReportDefinitionSource;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.SheetDefinition;
import ru.ludwigandreas.export.api.SortKey;
import ru.ludwigandreas.export.api.StandardReportFormats;
import ru.ludwigandreas.export.exception.ExportConfigurationException;
import ru.ludwigandreas.export.exception.UnknownReportDefinitionException;
import ru.ludwigandreas.export.registry.MessageKeyValidator;
import ru.ludwigandreas.export.registry.ReportDefinitionRegistry;
import ru.ludwigandreas.export.unit.TestReports.OrderRow;

/**
 * The startup failures, one per way a definition can be wrong against the rest of the estate.
 *
 * <p>Each of these is a mistake that would otherwise produce a report that is wrong rather than one
 * that fails, which is why they are worth a refused context: the whole argument for validating at
 * startup is that these are cheap to catch now and expensive to notice later.
 */
class ReportDefinitionRegistryTest {

    private static final Map<String, ReportFormat> BOTH_FORMATS = Map.of(
            "xlsx", StandardReportFormats.XLSX,
            "csv", StandardReportFormats.CSV);

    /** Resolves everything, so a test that is not about message keys is not about message keys. */
    private static final MessageKeyValidator ALL_KEYS_RESOLVE = (key, locale) -> true;

    private static ReportDefinitionSource source(ReportDefinition<?, ?>... definitions) {
        List<ReportDefinition<?, ?>> list = List.of(definitions);
        return () -> list;
    }

    private static ReportDefinitionRegistry registry(Map<String, ReportFormat> formats,
                                                     MessageKeyValidator keys,
                                                     ReportDefinitionSource... sources) {
        return new ReportDefinitionRegistry(List.of(sources), formats, keys);
    }

    @Test
    @DisplayName("a well-formed estate registers and is queryable by key")
    void registersAValidEstate() {
        var definition = TestReports.orders().build();
        var registry = registry(BOTH_FORMATS, ALL_KEYS_RESOLVE, source(definition));

        assertThat(registry.definitions()).containsExactly(definition);
        assertThat(registry.find("catalog.orders")).contains(definition);
        assertThat(registry.find("catalog.nothing")).isEmpty();
        assertThat(registry.require("catalog.orders")).isSameAs(definition);
    }

    @Test
    @DisplayName("requiring a key nothing registered is a localized 404, not a null")
    void requireFailsForAnUnknownKey() {
        var registry = registry(BOTH_FORMATS, ALL_KEYS_RESOLVE);

        assertThatThrownBy(() -> registry.require("catalog.gone"))
                .isInstanceOf(UnknownReportDefinitionException.class)
                .hasMessageContaining("catalog.gone");
    }

    @Test
    @DisplayName("the same key from two sources is refused, naming both declarations")
    void rejectsDuplicateKeysAcrossSources() {
        var first = TestReports.orders().build();
        var second = TestReports.orders().version(2).build();

        assertThatThrownBy(() -> registry(BOTH_FORMATS, ALL_KEYS_RESOLVE, source(first), source(second)))
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("declared twice")
                .hasMessageContaining("catalog.orders");
    }

    @Test
    @DisplayName("a column fed by a stage the definition does not declare is refused")
    void rejectsColumnFedByUndeclaredStage() {
        Column<OrderRow, String> enriched = Column.<OrderRow, String>of("customer-name", String.class)
                .extractor(OrderRow::customerId)
                .headerKey("test.column.customer-name")
                .format(CellFormat.text())
                .requiredStage("customer")
                .build();
        var definition = TestReports.orders().column(enriched).build();

        assertThatThrownBy(() -> registry(BOTH_FORMATS, ALL_KEYS_RESOLVE, source(definition)))
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("customer-name")
                .hasMessageContaining("does not declare");
    }

    @Test
    @DisplayName("a stage depending on a stage nobody declared is refused")
    void rejectsStageWithUnknownDependency() {
        var stage = EnrichmentStage.<OrderRow, UUID, String>of("customer", batched())
                .keyExtractor(row -> UUID.nameUUIDFromBytes(row.customerId().getBytes()))
                .merge((row, value) -> row)
                .dependsOn(Set.of("tier"))
                .build();
        var definition = TestReports.orders().stage(stage).build();

        assertThatThrownBy(() -> registry(BOTH_FORMATS, ALL_KEYS_RESOLVE, source(definition)))
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("depends on 'tier'");
    }

    @Test
    @DisplayName("a default sort the source cannot order by is refused, naming what it can")
    void rejectsUnsortableDefaultSort() {
        var definition = TestReports.orders().defaultSort(List.of(SortKey.asc("total"))).build();

        assertThatThrownBy(() -> registry(BOTH_FORMATS, ALL_KEYS_RESOLVE, source(definition)))
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("cannot order by")
                .hasMessageContaining("sortable: number");
    }

    @Test
    @DisplayName("a source declaring a sortable column the definition does not have is refused")
    void rejectsSortableColumnWithNoDefinition() {
        var definition = TestReports.orders()
                .source(new TestReports.FixedSource(List.of(), Set.of("number", "placed-on")))
                .build();

        assertThatThrownBy(() -> registry(BOTH_FORMATS, ALL_KEYS_RESOLVE, source(definition)))
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("placed-on");
    }

    @Test
    @DisplayName("a format with no registered writer is refused, naming what is registered")
    void rejectsFormatWithoutAWriter() {
        var definition = TestReports.orders().build();

        assertThatThrownBy(() -> registry(Map.of("csv", StandardReportFormats.CSV),
                ALL_KEYS_RESOLVE, source(definition)))
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("no ReportWriterFactory is registered");
    }

    @Test
    @DisplayName("several sheets plus a flat format under REJECT is refused at startup, not per request")
    void rejectsMultiSheetIntoFlatFormat() {
        var definition = TestReports.orders()
                .clearSheets()
                .sheet(SheetDefinition.of("open", "test.sheet.open", row -> "open", true))
                .sheet(SheetDefinition.of("closed", "test.sheet.closed", row -> "closed", false))
                .multiSheetStrategy(MultiSheetStrategy.REJECT)
                .build();

        assertThatThrownBy(() -> registry(BOTH_FORMATS, ALL_KEYS_RESOLVE, source(definition)))
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("MultiSheetStrategy.REJECT");
    }

    @Test
    @DisplayName("the same definition under ZIP is accepted")
    void acceptsMultiSheetIntoFlatFormatUnderZip() {
        var definition = TestReports.orders()
                .clearSheets()
                .sheet(SheetDefinition.of("open", "test.sheet.open", row -> "open", true))
                .sheet(SheetDefinition.of("closed", "test.sheet.closed", row -> "closed", false))
                .multiSheetStrategy(MultiSheetStrategy.ZIP)
                .build();

        assertThatCode(() -> registry(BOTH_FORMATS, ALL_KEYS_RESOLVE, source(definition)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a header key missing from the Russian bundle is refused, naming the locale")
    void rejectsKeyMissingFromOneLocale() {
        MessageKeyValidator englishOnly = (key, locale) -> Locale.ENGLISH.getLanguage().equals(locale.getLanguage());
        var definition = TestReports.orders().build();

        assertThatThrownBy(() -> registry(BOTH_FORMATS, englishOnly, source(definition)))
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("does not resolve for locale 'ru'")
                .hasMessageContaining("test.column.number");
    }

    @Test
    @DisplayName("a syntactically impossible authority is refused, because it hides the column forever")
    void rejectsMalformedAuthority() {
        Column<OrderRow, String> restricted = Column.<OrderRow, String>of("note", String.class)
                .extractor(OrderRow::number)
                .headerKey("test.column.note")
                .visibleFor(Set.of("role_finance"))
                .build();
        var definition = TestReports.orders().column(restricted).build();

        assertThatThrownBy(() -> registry(BOTH_FORMATS, ALL_KEYS_RESOLVE, source(definition)))
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("hidden from everyone");
    }

    @Test
    @DisplayName("every problem is reported together, not one per restart")
    void reportsEveryProblemAtOnce() {
        Column<OrderRow, String> enriched = Column.<OrderRow, String>of("customer-name", String.class)
                .extractor(OrderRow::customerId)
                .headerKey("test.column.customer-name")
                .requiredStage("customer")
                .build();
        var definition = TestReports.orders()
                .column(enriched)
                .defaultSort(List.of(SortKey.asc("total")))
                .requiredAuthorities(Set.of("reports"))
                .build();

        assertThatThrownBy(() -> registry(BOTH_FORMATS, ALL_KEYS_RESOLVE, source(definition)))
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("customer-name")
                .hasMessageContaining("cannot order by")
                .hasMessageContaining("nobody can ever run this report");
    }

    @Test
    @DisplayName("a source contributing null is named rather than silently skipped")
    void rejectsNullContribution() {
        ReportDefinitionSource broken = new ReportDefinitionSource() {
            @Override
            public Collection<ReportDefinition<?, ?>> definitions() {
                return null;
            }
        };

        assertThatThrownBy(() -> registry(BOTH_FORMATS, ALL_KEYS_RESOLVE, broken))
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("returned null");
    }

    private static Enricher.Batched<UUID, String> batched() {
        return keys -> Map.of();
    }

    /** Unused helper kept honest: {@link Optional} is what {@code find} returns. */
    @Test
    @DisplayName("find returns an Optional rather than null for a missing key")
    void findReturnsOptional() {
        Optional<ReportDefinition<?, ?>> found = registry(BOTH_FORMATS, ALL_KEYS_RESOLVE).find("nothing");

        assertThat(found).isEmpty();
    }
}
