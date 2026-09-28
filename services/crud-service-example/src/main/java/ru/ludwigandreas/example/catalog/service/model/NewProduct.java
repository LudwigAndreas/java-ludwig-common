package ru.ludwigandreas.example.catalog.service.model;

import java.math.BigDecimal;
import java.util.UUID;

/** Everything needed to create a product. Validation already happened in the web layer. */
public record NewProduct(
        String sku,
        String name,
        String description,
        BigDecimal price,
        BigDecimal supplierCost,
        ProductState state,
        int stockQuantity,
        UUID categoryId) {
}
