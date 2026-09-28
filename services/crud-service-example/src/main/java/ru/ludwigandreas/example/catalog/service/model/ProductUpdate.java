package ru.ludwigandreas.example.catalog.service.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A full replacement of a product's mutable state. The SKU is absent on purpose - it is the natural
 * key and immutable once assigned.
 *
 * @param expectedVersion the version the client last read; the update is rejected if the stored row
 *                        has moved on since
 */
public record ProductUpdate(
        String name,
        String description,
        BigDecimal price,
        BigDecimal supplierCost,
        ProductState state,
        int stockQuantity,
        UUID categoryId,
        long expectedVersion) {
}
