package ru.ludwigandreas.example.catalog.web.dto;

/**
 * Product lifecycle as it appears on the wire. Separate from the service enum on purpose: the API
 * contract is allowed to outlive an internal rename, and MapStruct proves at compile time that the
 * two still line up.
 */
public enum ProductStatusDto {
    DRAFT,
    ACTIVE,
    DISCONTINUED
}
