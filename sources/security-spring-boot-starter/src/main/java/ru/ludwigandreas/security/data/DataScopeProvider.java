package ru.ludwigandreas.security.data;

import ru.ludwigandreas.security.principal.LudwigPrincipal;

/**
 * Decides which rows a caller may touch, before any row is read.
 *
 * <p>Separate from {@link ru.ludwigandreas.security.authz.AuthorityResolver} because the two answer
 * different questions and change at different rates. "May this caller call {@code GET /orders} at
 * all?" is a role question, answered once per principal and cached. "Which orders come back?" is a
 * data question, answered per resource and per action, and it is the one that has to be recomputed
 * whenever the resource being queried changes.
 *
 * <p>Multiple providers may be registered - the configured role defaults from
 * {@link PolicyDataScopeProvider} plus, say, explicit per-user grant rows from the identity
 * projection. {@link CompositeDataScopeProvider} unions their answers.
 *
 * <p>Implementations must be fail-closed: when in doubt, return {@link DataScope#none()}. Returning
 * {@link DataScope#all()} because a lookup failed is how every row of a table ends up in a response.
 */
@FunctionalInterface
public interface DataScopeProvider {

    /**
     * @param resourceType the logical resource name a {@link DataScopeMapping} is registered under
     *                     ({@code "order"}, {@code "product"}) - never a table or class name, so the
     *                     policy survives a rename
     * @param action       what the caller is trying to do: {@link DataAction#READ},
     *                     {@link DataAction#WRITE}, {@link DataAction#DELETE} or a service-specific verb
     */
    DataScope scopeFor(LudwigPrincipal principal, String resourceType, String action);
}
