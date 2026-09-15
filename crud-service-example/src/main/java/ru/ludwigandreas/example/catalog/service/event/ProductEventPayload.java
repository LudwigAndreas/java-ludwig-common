package ru.ludwigandreas.example.catalog.service.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body of every product event written to the transactional outbox.
 *
 * <p>It is an explicit, stable contract rather than a serialized entity or domain object, so an
 * internal refactor can't silently reshape what downstream consumers receive.
 */
public record ProductEventPayload(
        UUID id,
        String sku,
        String name,
        BigDecimal price,
        String status,
        String categoryCode) {
}
