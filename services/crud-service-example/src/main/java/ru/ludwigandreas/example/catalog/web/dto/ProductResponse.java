package ru.ludwigandreas.example.catalog.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Product as returned to clients - the outermost of this service's three models.
 *
 * <p>{@code supplierCost} is intentionally not part of it: what a product costs us is internal, and
 * a field that never leaves the service can't leak through this API. (Catalog admins can still
 * filter on it - see {@code ProductEntity}.)
 */
public record ProductResponse(
        UUID id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        ProductStatusDto status,
        int stockQuantity,
        CategoryResponse category,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
