package ru.ludwigandreas.example.catalog.service.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import ru.ludwigandreas.example.catalog.client.dto.SupplierSummary;
import ru.ludwigandreas.example.catalog.repository.entity.ProductEntity;
import ru.ludwigandreas.example.catalog.repository.entity.ProductStatus;
import ru.ludwigandreas.export.api.Aggregate;
import ru.ludwigandreas.export.api.CallIdentity;
import ru.ludwigandreas.export.api.CellFormat;
import ru.ludwigandreas.export.api.Column;
import ru.ludwigandreas.export.api.EnrichmentStage;
import ru.ludwigandreas.export.api.FailurePolicy;
import ru.ludwigandreas.export.api.MissingPolicy;
import ru.ludwigandreas.export.api.NullPolicy;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.api.ReportDefinitionSource;
import ru.ludwigandreas.export.api.SortKey;
import ru.ludwigandreas.export.api.StandardReportFormats;

/**
 * The reports this service publishes.
 *
 * <h2>Why a definition is code</h2>
 *
 * <p>Everything about this report that could be wrong is wrong at startup rather than at download
 * time. A column whose extractor does not compile against the row type is a compile error. A column
 * fed by an enrichment stage that does not exist, a default sort the source cannot order by, a
 * declared format nothing can write, a header key missing from the Russian bundle, a stage naming a
 * REST client the deployment does not configure, a row cap above the format's own sheet ceiling - all
 * of those fail the application context, naming every problem at once.
 *
 * <p>The alternative - rows in a table describing columns by name - moves each of those failures to
 * the first person who requests the report, in a file they have already been told is ready.
 *
 * <h2>What this definition is demonstrating</h2>
 *
 * <p>It is deliberately not the simplest possible report. It exercises the four things a second report
 * in this estate will need and that nothing else in this repository shows:
 *
 * <ul>
 *   <li>a column visible only to a role, which is <em>absent from the file</em> for everyone else
 *       rather than present and blank;</li>
 *   <li>an enrichment stage against another service, with an explicit policy for a key that service
 *       does not know and a different one for the service being down;</li>
 *   <li>filtering delegated to the entity's own {@code @Filterable} annotations rather than restated;</li>
 *   <li>row-level scope taken from the {@code product} data-scope mapping, so a watcher exporting the
 *       catalogue gets the products they watch and not the catalogue.</li>
 * </ul>
 */
@Component
public class CatalogReports implements ReportDefinitionSource {

    /** The key the report is requested under, stored on every run and used as a metric tag. */
    public static final String PRODUCTS = "catalog.products";

    /** The enrichment stage's name, which the enriched columns name as their source. */
    public static final String STAGE_SUPPLIER = "supplier";

    static final String COLUMN_SKU = "sku";
    static final String COLUMN_NAME = "name";
    static final String COLUMN_CATEGORY = "category";
    static final String COLUMN_STATUS = "status";
    static final String COLUMN_PRICE = "price";
    static final String COLUMN_SUPPLIER_COST = "supplier-cost";
    static final String COLUMN_STOCK = "stock";
    static final String COLUMN_SUPPLIER_NAME = "supplier-name";
    static final String COLUMN_SUPPLIER_RATING = "supplier-rating";
    static final String COLUMN_SUPPLIER_ON_TIME = "supplier-on-time";
    static final String COLUMN_UPDATED_AT = "updated-at";

    /**
     * The role that may see purchase prices.
     *
     * <p>The same authority the entity's {@code supplierCost} field already restricts for filtering.
     * Stating it twice is not duplication of a rule: one governs whether a caller may <em>narrow</em>
     * by the column, the other whether they may <em>read</em> it, and an estate has had both mistakes.
     */
    private static final String ROLE_CATALOG_ADMIN = "ROLE_CATALOG_ADMIN";

    private static final String TITLE_KEY = "catalog.report.products.title";
    private static final String HEADER_PREFIX = "catalog.report.products.column.";

    /**
     * The one currency this catalogue prices in.
     *
     * <p>A constant rather than a column because the catalogue has no currency column: every price in
     * it is in roubles, and a money cell needs to say which currency it is so that a spreadsheet
     * formats and sums it as money rather than as a number that happens to have two decimal places. A
     * multi-currency catalogue would need a per-row currency, which is what {@code CellFormat} models
     * separately and this report has no data for.
     */
    private static final Currency RUB = Currency.getInstance("RUB");

    /**
     * How many products may be exported in one run.
     *
     * <p>Far below the engine's own capability, and set here because it is a statement about this
     * report rather than about the engine: a catalogue extract that produced a quarter of a million
     * rows would be somebody's mistaken date window, and telling them so is more useful than serving
     * it. The number is on the definition rather than in configuration for the same reason - an
     * operator raising a global limit should not silently change what this report considers sane.
     */
    private static final long MAX_ROWS = 250_000L;

    /**
     * Keys per call to the supplier directory.
     *
     * <p>Below the module's default, because this partner's lookup is a {@code GET} with one query
     * parameter per id: the ceiling is the path length the directory's own server accepts, not
     * anything the engine knows about. A stage that inherited a larger default would fail with a 414
     * under exactly the load the batching exists to handle.
     */
    private static final int SUPPLIER_BATCH_SIZE = 100;

    /**
     * Concurrent in-flight calls to the directory during one window.
     *
     * <p>Must not exceed the {@code suppliers} client's {@code max-per-route} pool, and the export
     * module refuses to start if it does: calls beyond the pool do not fail, they queue inside it, and
     * a bounded fan-out quietly becomes an unbounded wait with no metric that shows it.
     */
    private static final int SUPPLIER_CONCURRENCY = 8;

    /**
     * Column widths, in characters, named rather than written at each call site.
     *
     * <p>Declared at all because the alternative is POI's {@code autoSizeColumn}, which measures every
     * value in a column and therefore needs the whole column in memory - the one thing a streaming
     * writer cannot do. Named because "14" appearing beside three different money columns says nothing,
     * whereas a shared constant says they are meant to line up.
     */
    private static final int WIDTH_NARROW = 10;
    private static final int WIDTH_SHORT = 12;
    private static final int WIDTH_MONEY = 14;
    private static final int WIDTH_CODE = 16;
    private static final int WIDTH_SKU = 18;
    private static final int WIDTH_TIMESTAMP = 20;
    private static final int WIDTH_LABEL = 28;
    private static final int WIDTH_TITLE = 40;

    private final ProductRowSource source;
    private final SupplierEnricher supplierEnricher;

    public CatalogReports(ProductRowSource source, SupplierEnricher supplierEnricher) {
        this.source = source;
        this.supplierEnricher = supplierEnricher;
    }

    @Override
    public Collection<ReportDefinition<?, ?>> definitions() {
        return List.of(products());
    }

    private ReportDefinition<ProductReportParameters, ProductReportRow> products() {
        return ReportDefinition.<ProductReportParameters, ProductReportRow>of(
                        PRODUCTS, ProductReportParameters.class, ProductReportRow.class)
                .version(1)
                .titleKey(TITLE_KEY)
                .source(source)
                .stage(supplierStage())
                .columns(columns())
                .allowedFormats(Set.of(StandardReportFormats.XLSX, StandardReportFormats.CSV))
                .defaultFormat(StandardReportFormats.XLSX)
                .maxRows(MAX_ROWS)
                .defaultSort(List.of(SortKey.asc(COLUMN_SKU)))
                // The four columns a caller may narrow by. What each of them accepts - which operators,
                // which roles - is not restated here: it is on ProductEntity's own @Filterable
                // annotations, which the OData parser reads. This list only says which of them this
                // report advertises.
                .filterableColumns(Set.of(COLUMN_SKU, COLUMN_NAME, COLUMN_PRICE, COLUMN_STATUS))
                .filterEntityType(ProductEntity.class)
                // The resource the product data-scope mapping is registered under, so an export is
                // scoped by the same rules a paged read is - including the custom "products I watch"
                // axis, which no annotation expresses.
                .scopeResourceType("product")
                .totalsRow(true)
                .build();
    }

    /**
     * The supplier directory join, and the two policies that decide what a reader sees when it fails.
     *
     * <p>The two are deliberately different, because the questions are different.
     *
     * <p><b>A supplier the directory does not know</b> is ordinary. Suppliers are retired and products
     * outlive them, so the report writes a localized marker in the three enriched cells and carries on.
     * The alternative - failing the row - would drop a product from the catalogue extract because of a
     * fact about its supplier, which is the wrong report.
     *
     * <p><b>The directory being unreachable</b> is not ordinary, and the choice here is
     * {@link FailurePolicy#DEGRADE} rather than failing the report: a catalogue extract is still useful
     * without supplier names, and a nightly run that produced nothing because a peer was restarting is
     * worse than one that produced ten columns out of thirteen. That is only an acceptable trade
     * because degradation is impossible to miss - the cells say so, the run row records the degraded
     * stage, the metadata sheet lists it, the download response carries a header and a counter moves.
     * A blank cell is never the only signal.
     */
    private EnrichmentStage<ProductReportRow, String, SupplierSummary> supplierStage() {
        return EnrichmentStage.<ProductReportRow, String, SupplierSummary>of(
                        STAGE_SUPPLIER, supplierEnricher)
                .keyExtractor(ProductReportRow::supplierId)
                .merge(ProductReportRow::withSupplier)
                .missingPolicy(MissingPolicy.placeholder("catalog.report.products.supplier.unknown"))
                .failurePolicy(FailurePolicy.DEGRADE)
                // Named so the startup validator can check the numbers below, and the identity above,
                // against what the deployment actually configured for this partner.
                .restClient("suppliers")
                // A configured integration: the deployment provisioned this service's own credentials
                // against the supplier directory, which is why `suppliers` uses
                // auth.type: oauth2-client-credentials. The directory trusts this service rather than
                // the person running the report, and that is the right shape here - a supplier's name
                // and rating are not personal to the requester, and a nightly subscription has no
                // requester's token to offer anyway.
                //
                // A partner whose data *is* per-caller - an orders service, a billing service - would be
                // declared CallIdentity.REQUESTER instead, with its client configured
                // auth.type: oauth2-token-relay. The startup validator refuses the two spelled
                // inconsistently, so this line and application.yml cannot drift apart.
                .callAs(CallIdentity.SERVICE_ACCOUNT)
                .batchSize(SUPPLIER_BATCH_SIZE)
                .concurrency(SUPPLIER_CONCURRENCY)
                .build();
    }

    /**
     * The columns, in the order they appear in the file.
     *
     * <p>Widths are declared rather than computed. Auto-sizing a column requires measuring every value
     * in it, which means holding the whole column in memory - the one thing a streaming writer must not
     * do, and the reason POI's {@code autoSizeColumn} is unusable at this scale.
     */
    private List<Column<ProductReportRow, ?>> columns() {
        return List.of(
                text(COLUMN_SKU, ProductReportRow::sku, WIDTH_SKU),
                text(COLUMN_NAME, ProductReportRow::name, WIDTH_TITLE),
                text(COLUMN_CATEGORY, ProductReportRow::categoryCode, WIDTH_CODE),
                Column.<ProductReportRow, ProductStatus>of(COLUMN_STATUS, ProductStatus.class)
                        .extractor(ProductReportRow::status)
                        .headerKey(HEADER_PREFIX + COLUMN_STATUS)
                        // Rendered through the enum's name(), not its toString(): a toString() somebody
                        // adds for a log line would silently change the contents of every file.
                        .format(CellFormat.text())
                        .width(WIDTH_MONEY)
                        .build(),
                Column.<ProductReportRow, BigDecimal>of(COLUMN_PRICE, BigDecimal.class)
                        .extractor(ProductReportRow::price)
                        .headerKey(HEADER_PREFIX + COLUMN_PRICE)
                        .format(CellFormat.money(RUB))
                        .width(WIDTH_MONEY)
                        .build(),
                Column.<ProductReportRow, BigDecimal>of(COLUMN_SUPPLIER_COST, BigDecimal.class)
                        .extractor(ProductReportRow::supplierCost)
                        .headerKey(HEADER_PREFIX + COLUMN_SUPPLIER_COST)
                        .format(CellFormat.money(RUB))
                        .width(WIDTH_MONEY)
                        // Absent from the file for anyone without the role, not blank. A blank column
                        // tells a reader that this product has no purchase price, which is a different
                        // and false statement.
                        .visibleFor(Set.of(ROLE_CATALOG_ADMIN))
                        .aggregate(Aggregate.SUM)
                        .build(),
                Column.<ProductReportRow, Integer>of(COLUMN_STOCK, Integer.class)
                        .extractor(ProductReportRow::stockQuantity)
                        .headerKey(HEADER_PREFIX + COLUMN_STOCK)
                        .format(CellFormat.number(0))
                        .width(WIDTH_NARROW)
                        .aggregate(Aggregate.SUM)
                        .build(),
                enriched(COLUMN_SUPPLIER_NAME, row -> supplierField(row, SupplierSummary::name), WIDTH_LABEL),
                enriched(COLUMN_SUPPLIER_RATING,
                        row -> supplierField(row, SupplierSummary::ratingClass), WIDTH_SHORT),
                Column.<ProductReportRow, BigDecimal>of(COLUMN_SUPPLIER_ON_TIME, BigDecimal.class)
                        .extractor(row -> row.supplier() == null ? null : row.supplier().onTimeRate())
                        .headerKey(HEADER_PREFIX + COLUMN_SUPPLIER_ON_TIME)
                        .format(CellFormat.percent(1))
                        .width(WIDTH_SHORT)
                        .requiredStage(STAGE_SUPPLIER)
                        // Not ZERO: a supplier whose punctuality is unknown is not a supplier that
                        // never delivers on time, and a zero here would be read as the latter.
                        .nullPolicy(NullPolicy.EMPTY)
                        .build(),
                Column.<ProductReportRow, Instant>of(COLUMN_UPDATED_AT, Instant.class)
                        .extractor(ProductReportRow::updatedAt)
                        .headerKey(HEADER_PREFIX + COLUMN_UPDATED_AT)
                        .format(CellFormat.dateTime())
                        .width(WIDTH_TIMESTAMP)
                        .build());
    }

    private static Column<ProductReportRow, String> text(
            String id, Function<ProductReportRow, String> extractor, int width) {
        return Column.<ProductReportRow, String>of(id, String.class)
                .extractor(extractor)
                .headerKey(HEADER_PREFIX + id)
                .format(CellFormat.text())
                .width(width)
                .build();
    }

    private static Column<ProductReportRow, String> enriched(
            String id, Function<ProductReportRow, String> extractor, int width) {
        return Column.<ProductReportRow, String>of(id, String.class)
                .extractor(extractor)
                .headerKey(HEADER_PREFIX + id)
                .format(CellFormat.text())
                .width(width)
                // Declaring the stage is what lets the engine mark this cell - rather than leave it
                // blank - when the stage degraded, and what makes a column fed by a stage nobody
                // declared a startup failure instead of a column of nulls.
                .requiredStage(STAGE_SUPPLIER)
                .build();
    }

    private static String supplierField(ProductReportRow row,
                                        Function<SupplierSummary, String> field) {
        return row.supplier() == null ? null : field.apply(row.supplier());
    }
}
