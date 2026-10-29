package ru.ludwigandreas.security.admin;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A read of an administrative object outside the caller's scope, answered as the object not existing.
 *
 * <p>Rendered with the area's own not-found code and the object name ({@link AdminObject#notFound()}), so the body is
 * the one a missing object gets: a caller cannot tell an object they may not see from one that is not there.
 */
public final class AdminObjectNotFoundException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /** The code used when the handler names no area-specific one. */
    public static final String DEFAULT_CODE = "ludwig.admin.object-not-found";

    /**
     * Creates the exception.
     *
     * @param code   the area's not-found problem code
     * @param object the object name
     */
    public AdminObjectNotFoundException(String code, String object) {
        super(ProblemStatus.NOT_FOUND, code, object);
    }
}
