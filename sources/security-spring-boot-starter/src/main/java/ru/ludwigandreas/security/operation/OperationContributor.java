package ru.ludwigandreas.security.operation;

import java.util.Collection;

/**
 * Declares the operations a module performs. A module registers one as a bean; {@link OperationCatalog} collects
 * every contributor and validates the union at startup.
 */
public interface OperationContributor {

    /**
     * The operations this module performs.
     *
     * @return them, each with a distinct id
     */
    Collection<Operation> operations();
}
