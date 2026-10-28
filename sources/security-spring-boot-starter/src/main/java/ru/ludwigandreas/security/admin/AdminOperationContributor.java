package ru.ludwigandreas.security.admin;

import java.util.Collection;

/**
 * Declares the administrative operations a module offers.
 *
 * <p>A module registers one as a bean; {@link AdminOperationCatalog} collects every contributor in the context and
 * validates the union at startup. A module declaring any operation of an area declares all three of its tiers, so
 * a service's grant block for an area is the same three lines in every release.
 */
public interface AdminOperationContributor {

    /**
     * The operations this module offers.
     *
     * @return the operations, each with a distinct id
     */
    Collection<AdminOperation> adminOperations();
}
