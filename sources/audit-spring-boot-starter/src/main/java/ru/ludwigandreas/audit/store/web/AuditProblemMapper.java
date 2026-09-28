package ru.ludwigandreas.audit.store.web;

import ru.ludwigandreas.audit.AuditWriteFailedException;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * States what an unwritable audit row means to a caller, so a service does not have to.
 *
 * <p>A 503 and not a 500. The distinction is the whole message: the request was rejected because the
 * trail could not be written, the change did not happen, and retrying is the correct response - which is
 * exactly what {@code Service Unavailable} tells a client and what {@code Internal Server Error} does
 * not. A client told "internal error" retries blindly or gives up; a client told "unavailable" retries
 * the way the deployment wants.
 *
 * <p>Contributed into {@code web-core}'s single RFC 9457 pipeline rather than shipped as this module's
 * own {@code @RestControllerAdvice}, like every other starter here.
 */
public class AuditProblemMapper implements ExceptionProblemMapper {

    /** Namespace for this module's codes, mirroring the {@code i18n} bundle's keys. */
    public static final String CODE_PREFIX = "ludwig.audit.error.";

    @Override
    public boolean supports(Throwable exception) {
        return exception instanceof AuditWriteFailedException;
    }

    @Override
    public ProblemDefinition map(Throwable exception) {
        return ProblemDefinition.of(ProblemStatus.SERVICE_UNAVAILABLE, CODE_PREFIX + "write-failed");
    }

    @Override
    public int getOrder() {
        return DEFAULT_MODULE_ORDER;
    }
}
