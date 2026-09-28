package ru.ludwigandreas.example.catalog.service.model;

/**
 * Product lifecycle as the business layer knows it. Its persistence twin is
 * {@code repository.entity.ProductStatus} and its wire twin is {@code web.dto.ProductStatusDto};
 * MapStruct maps between them by constant name at compile time, so adding a state to one without
 * the others fails the build.
 */
public enum ProductState {
    DRAFT,
    ACTIVE,
    DISCONTINUED
}
