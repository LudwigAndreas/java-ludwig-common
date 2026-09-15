package ru.ludwigandreas.example.catalog.service.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The service-layer model - the middle of this service's three models. Immutable, free of JPA and
 * of HTTP: the controller never sees {@code ProductEntity}, and the repository never sees a DTO.
 *
 * @param version optimistic-locking version, echoed to clients so they can send it back on update
 */
public record Product(
        UUID id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        BigDecimal supplierCost,
        ProductState state,
        int stockQuantity,
        Category category,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
