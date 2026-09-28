package ru.ludwigandreas.example.catalog.repository.query;

/**
 * Raw OData query options as they reach the repository layer.
 *
 * <p>Translating them into a predicate is deliberately the repository's job, not the service's or
 * the controller's: only this layer knows the entity model the OData property paths are resolved
 * against, so neither of the outer layers has to import {@code ProductEntity} to run a search.
 *
 * @param filter  OData {@code $filter} expression, or {@code null} for "no restriction"
 * @param orderBy OData {@code $orderby} expression, or {@code null} for the default ordering
 * @param top     page size ({@code $top}), or {@code null} for the entity's configured default
 * @param skip    number of rows to skip ({@code $skip}), or {@code null} for none
 */
public record ProductSearchCriteria(String filter, String orderBy, Integer top, Integer skip) {
}
