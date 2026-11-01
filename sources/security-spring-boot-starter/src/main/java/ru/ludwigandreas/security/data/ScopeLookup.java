package ru.ludwigandreas.security.data;

import ru.ludwigandreas.security.principal.LudwigPrincipal;

/**
 * Resolves the row scope of an operation, as {@link DataAccessGuard} needs it: which resource the operation's rows
 * belong to, and what the caller may touch of them. Implemented by the operation model; an interface here so that
 * row enforcement does not depend on how operations are catalogued and granted.
 */
public interface ScopeLookup {

    /**
     * The data-scope resource an operation touches.
     *
     * @param operationId a catalogued operation
     * @return its resource
     * @throws IllegalStateException when the operation is not catalogued or touches no rows
     */
    String resourceOf(String operationId);

    /**
     * The caller's scope for an operation: every role grant and every contributed per-subject grant, united.
     *
     * @param principal   the caller
     * @param operationId a catalogued operation
     * @return the scope; none when the caller does not hold the operation
     */
    DataScope scopeFor(LudwigPrincipal principal, String operationId);
}
