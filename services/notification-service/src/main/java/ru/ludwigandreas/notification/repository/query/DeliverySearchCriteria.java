package ru.ludwigandreas.notification.repository.query;

/**
 * Raw OData query options as they reach the repository layer.
 *
 * <p>Translating them into a predicate is the repository's job rather than the service's or the
 * controller's, because only this layer knows the entity model the property paths resolve against -
 * so neither outer layer has to import {@code NotificationDeliveryEntity} to run a search.
 *
 * @param filter  OData {@code $filter} expression, or {@code null} for no restriction
 * @param orderBy OData {@code $orderby} expression, or {@code null} for newest-first
 * @param top     page size ({@code $top}), or {@code null} for the entity's configured default
 * @param skip    rows to skip ({@code $skip}), or {@code null} for none
 */
public record DeliverySearchCriteria(String filter, String orderBy, Integer top, Integer skip) {
}
