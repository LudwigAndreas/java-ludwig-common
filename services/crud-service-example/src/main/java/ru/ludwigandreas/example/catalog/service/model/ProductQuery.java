package ru.ludwigandreas.example.catalog.service.model;

/**
 * A product search as the business layer expresses it: the OData options the caller supplied,
 * still unparsed. The repository layer owns their translation into a predicate.
 */
public record ProductQuery(String filter, String orderBy, Integer top, Integer skip) {
}
