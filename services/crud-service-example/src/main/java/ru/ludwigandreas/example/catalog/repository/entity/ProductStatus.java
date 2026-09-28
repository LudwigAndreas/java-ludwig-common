package ru.ludwigandreas.example.catalog.repository.entity;

/**
 * Persistence-layer product lifecycle, stored as a string column.
 *
 * <p>Each layer owns its own enum (see {@code service.model.ProductState} and
 * {@code web.dto.ProductStatusDto}); MapStruct maps between them by constant name and fails the
 * build - not a request - if the sets ever drift apart.
 */
public enum ProductStatus {
    DRAFT,
    ACTIVE,
    DISCONTINUED
}
