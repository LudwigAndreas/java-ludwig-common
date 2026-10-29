package ru.ludwigandreas.db.core.query;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A {@code $filter} sent to an administrative listing in a service that does not run the OData starter.
 */
public final class AdminFilterUnavailableException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /** The problem code. */
    public static final String CODE = "ludwig.admin.filter-unavailable";

    /** Creates the exception. */
    public AdminFilterUnavailableException() {
        super(ProblemStatus.INVALID, CODE);
    }
}
