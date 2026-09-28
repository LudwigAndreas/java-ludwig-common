package ru.ludwigandreas.webcore.operation;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemCodes;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The operation id in the URL addresses nothing this caller may see.
 *
 * <p>A {@link LocalizedException} rather than a mapper contribution, so it travels
 * {@code web-core}'s existing {@code ProblemDetail} pipeline with no {@code @RestControllerAdvice}
 * anywhere and renders identically to every other {@code 404} the service produces.
 *
 * <p>Deliberately the same answer for "no such operation" and "somebody else's operation". A
 * {@code 403} confirms the operation exists, which for a resource addressed by an opaque id is the
 * only thing an enumeration attempt could learn. Export's run endpoints already behave this way and
 * the reasoning is the module's, not this class's - lifting it here is what makes it the platform's.
 */
public class OperationNotFoundException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param operationId the id that was asked for, echoed back so a client polling several knows
     *                    which one it got wrong
     */
    public OperationNotFoundException(String operationId) {
        super(ProblemStatus.NOT_FOUND, ProblemCodes.OPERATION_NOT_FOUND, operationId);
        withProperty("operationId", operationId);
    }
}
