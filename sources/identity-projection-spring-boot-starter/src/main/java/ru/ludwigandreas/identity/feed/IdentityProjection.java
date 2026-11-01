package ru.ludwigandreas.identity.feed;

import java.util.List;

/**
 * The one way users and their roles get into the identity projection. Every feed - a Kafka subscription, a pulled
 * directory, a service's own webhook, a local seed - writes through this, so ordering, staleness, ownership, role
 * mapping, contact policy, authority-cache eviction and the disabled-principal announcement are the same whatever the
 * provider.
 *
 * <p>Each method runs in a transaction and joins one that is already active, so a feed can commit a page of changes
 * together with its own bookkeeping.
 */
public interface IdentityProjection {

    /**
     * Applies one change.
     *
     * @param change the change
     * @return what was done with it
     * @throws IllegalArgumentException when the change names a source that is not declared
     */
    ApplyOutcome apply(IdentityChange change);

    /**
     * Applies changes in order, in one transaction.
     *
     * @param changes the changes
     * @return each change's outcome, in the same order
     * @throws IllegalArgumentException when a change names a source that is not declared; nothing is applied
     */
    List<ApplyOutcome> applyAll(List<? extends IdentityChange> changes);

    /**
     * Starts a complete listing of one source, which disables every active user of that source it does not list.
     *
     * @param source the declared source being listed
     * @return the sweep, to be fed the listed subjects and then completed or abandoned
     * @throws IllegalArgumentException when the source is not declared
     */
    IdentitySweep beginSweep(String source);
}
