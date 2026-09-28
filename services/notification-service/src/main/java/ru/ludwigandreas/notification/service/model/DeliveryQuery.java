package ru.ludwigandreas.notification.service.model;

/**
 * A delivery search as the business layer expresses it: the OData options the caller supplied, still
 * unparsed.
 *
 * <p>The repository layer owns their translation into a predicate, and this type exists so the web
 * layer need not name {@code repository.query.DeliverySearchCriteria} to ask for a search. That is
 * not bookkeeping: a controller holding a repository type is a controller that has reached into the
 * persistence layer, and the next thing it reaches for is the entity.
 *
 * @param filter  OData {@code $filter} expression, or {@code null} for no restriction
 * @param orderBy OData {@code $orderby} expression, or {@code null} for newest-first
 * @param top     page size ({@code $top}), or {@code null} for the configured default
 * @param skip    rows to skip ({@code $skip}), or {@code null} for none
 */
public record DeliveryQuery(String filter, String orderBy, Integer top, Integer skip) {
}
