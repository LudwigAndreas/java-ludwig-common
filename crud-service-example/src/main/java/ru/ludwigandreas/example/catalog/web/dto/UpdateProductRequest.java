package ru.ludwigandreas.example.catalog.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Update payload. The SKU is deliberately absent - it is the product's natural key.
 *
 * @param version the version the client last read; sending a stale one is rejected with 409 instead
 *                of silently overwriting somebody else's change
 */
public record UpdateProductRequest(

        @NotBlank(message = "{catalog.validation.product.name.required}")
        @Size(max = 255, message = "{catalog.validation.product.name.size}")
        String name,

        @Size(max = 2000, message = "{catalog.validation.product.description.size}")
        String description,

        @NotNull(message = "{catalog.validation.product.price.required}")
        @DecimalMin(value = "0.00", message = "{catalog.validation.product.price.min}")
        @Digits(integer = 17, fraction = 2, message = "{catalog.validation.product.price.digits}")
        BigDecimal price,

        @DecimalMin(value = "0.00", message = "{catalog.validation.product.supplier-cost.min}")
        @Digits(integer = 17, fraction = 2, message = "{catalog.validation.product.supplier-cost.digits}")
        BigDecimal supplierCost,

        @NotNull(message = "{catalog.validation.product.status.required}")
        ProductStatusDto status,

        @PositiveOrZero(message = "{catalog.validation.product.stock.min}")
        int stockQuantity,

        @NotNull(message = "{catalog.validation.product.category.required}")
        UUID categoryId,

        @PositiveOrZero(message = "{catalog.validation.product.version.min}")
        long version) {
}
