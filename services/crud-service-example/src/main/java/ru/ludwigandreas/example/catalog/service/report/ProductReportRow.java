package ru.ludwigandreas.example.catalog.service.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import ru.ludwigandreas.example.catalog.client.dto.SupplierSummary;
import ru.ludwigandreas.example.catalog.repository.entity.ProductStatus;

/**
 * One line of the catalogue extract, at whatever stage of assembly it has reached.
 *
 * <h2>A projection, not the entity</h2>
 *
 * <p>The row the report walks is deliberately not {@code ProductEntity}. Three reasons, in order of
 * how much they cost when ignored:
 *
 * <p>Streaming a million managed entities through a Hibernate session accumulates a million objects
 * in the persistence context, which is the single easiest way to exhaust the heap the export engine
 * carefully budgeted. A projection is detached by construction - there is nothing to clear.
 *
 * <p>An entity carries lazy associations, and a column extractor that touches one is a query per row
 * that nothing in the report declares. Selecting {@code category.code} in the query makes the join
 * explicit and the cost visible.
 *
 * <p>An entity is mutable, and the enrichment stage's merge function must not mutate its argument -
 * the engine may hand the same row to more than one stage. A record with a wither cannot.
 *
 * @param id           the product, for logging and for the keyset tie-break
 * @param sku          the catalogue number
 * @param name         the display name
 * @param categoryCode the category's code, joined in the base query rather than enriched
 * @param status       the lifecycle status
 * @param price        the list price
 * @param supplierCost the purchase price, written only for a requester holding
 *                     {@code ROLE_CATALOG_ADMIN} - see the column's {@code visibleFor}
 * @param stockQuantity the units on hand
 * @param updatedAt    when the product last changed, which is what the report's window is over
 * @param supplierId   the supplier's identifier, the enrichment stage's key; null for a product with
 *                     no supplier, which the engine reads as "this row has no key" and leaves alone
 * @param supplier     what the supplier directory answered, or null before the stage has run and for
 *                     a key it did not resolve
 */
public record ProductReportRow(
        UUID id,
        String sku,
        String name,
        String categoryCode,
        ProductStatus status,
        BigDecimal price,
        BigDecimal supplierCost,
        int stockQuantity,
        Instant updatedAt,
        String supplierId,
        SupplierSummary supplier) {

    /**
     * The row as the base query produces it, before any stage has run.
     *
     * <p>The constructor the QueryDSL projection binds to. It exists so that the query need not select
     * a NULL literal for a field no query can fill: the enriched value arrives from a partner, and a
     * ten-column {@code SELECT} says that more plainly than an eleventh column that is always null.
     *
     * @param id            the product
     * @param sku           the catalogue number
     * @param name          the display name
     * @param categoryCode  the category's code
     * @param status        the lifecycle status
     * @param price         the list price
     * @param supplierCost  the purchase price
     * @param stockQuantity the units on hand
     * @param updatedAt     when the product last changed
     * @param supplierId    the supplier's identifier, the enrichment key
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a projection constructor, called only by QueryDSL's
    // Projections.constructor, which binds positionally from the SELECT list above it. The rule
    // protects hand-written call sites, and there are none.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ProductReportRow(UUID id, String sku, String name, String categoryCode, ProductStatus status,
                            BigDecimal price, BigDecimal supplierCost, int stockQuantity,
                            Instant updatedAt, String supplierId) {
        this(id, sku, name, categoryCode, status, price, supplierCost, stockQuantity, updatedAt,
                supplierId, null);
    }

    /**
     * This row with the supplier directory's answer attached.
     *
     * <p>The merge function the enrichment stage declares. Returning a new record rather than
     * assigning a field is not a style preference: the engine is free to apply stages to the same row
     * from more than one thread, and the contract on {@code EnrichmentStage.merge} says so.
     *
     * @param resolved what the directory returned for {@link #supplierId}
     * @return a copy carrying it
     */
    public ProductReportRow withSupplier(SupplierSummary resolved) {
        return new ProductReportRow(id, sku, name, categoryCode, status, price, supplierCost,
                stockQuantity, updatedAt, supplierId, resolved);
    }
}
