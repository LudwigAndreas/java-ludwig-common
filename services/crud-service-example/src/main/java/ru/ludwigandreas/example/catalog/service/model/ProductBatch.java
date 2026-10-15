package ru.ludwigandreas.example.catalog.service.model;

import java.util.List;

/**
 * The products of one batch lookup, in the order they were asked for.
 *
 * @param products the products
 */
public record ProductBatch(List<Product> products) {
}
