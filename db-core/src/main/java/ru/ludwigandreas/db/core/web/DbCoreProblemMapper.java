package ru.ludwigandreas.db.core.web;

import ru.ludwigandreas.db.core.exception.DuplicateEntityException;
import ru.ludwigandreas.db.core.exception.EntityNotFoundException;
import ru.ludwigandreas.db.core.exception.IntegrityViolationException;
import ru.ludwigandreas.db.core.exception.UnsupportedIdTypeException;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * States what this module's exceptions mean, so a service does not have to.
 *
 * <p>{@link EntityNotFoundException} is the one that matters. {@code getByIdOrThrow} raises it, and
 * a service layer normally converts a missing row into its own localized exception first - but only
 * on the paths where someone remembered to. Everywhere else it reached the advice unmapped and was
 * answered as a 500: a missing row reported as a server fault, on exactly the code paths nobody had
 * reviewed. Mapping it here makes the safety net the default rather than something each service
 * writes for itself.
 *
 * <p>The messages are deliberately generic - "the requested resource does not exist" - because this
 * module knows the entity class and not what the API calls it. A service that wants "no product
 * exists with id X" throws its own {@code LocalizedException} with its own code, which is what the
 * exception's higher precedence in the mapper chain is for.
 */
public class DbCoreProblemMapper implements ExceptionProblemMapper {

    /** Namespace for this module's codes, mirroring the {@code i18n} bundle's keys. */
    public static final String CODE_PREFIX = "ludwig.db.error.";

    @Override
    public boolean supports(Throwable exception) {
        return exception instanceof EntityNotFoundException
                || exception instanceof DuplicateEntityException
                || exception instanceof IntegrityViolationException
                || exception instanceof UnsupportedIdTypeException;
    }

    @Override
    public ProblemDefinition map(Throwable exception) {
        if (exception instanceof EntityNotFoundException) {
            return ProblemDefinition.of(ProblemStatus.NOT_FOUND, CODE_PREFIX + "entity-not-found");
        }
        if (exception instanceof DuplicateEntityException) {
            return ProblemDefinition.of(ProblemStatus.CONFLICT, CODE_PREFIX + "duplicate-entity");
        }
        if (exception instanceof IntegrityViolationException) {
            return ProblemDefinition.of(ProblemStatus.CONFLICT, CODE_PREFIX + "integrity-violation");
        }
        // An id type this module cannot convert is a wiring bug in the service, not a client error:
        // the caller sent a path variable and has no way to send a different kind of one.
        return ProblemDefinition.of(ProblemStatus.INTERNAL, CODE_PREFIX + "unsupported-id-type");
    }

    @Override
    public int getOrder() {
        return DEFAULT_MODULE_ORDER;
    }
}
