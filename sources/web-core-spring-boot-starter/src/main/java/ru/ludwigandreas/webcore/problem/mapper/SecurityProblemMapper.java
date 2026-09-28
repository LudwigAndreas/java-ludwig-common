package ru.ludwigandreas.webcore.problem.mapper;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemCodes;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Answers authorization and authentication failures that reach a controller.
 *
 * <p>This mapper exists because {@code @PreAuthorize} and any data-level guard throw <em>inside</em>
 * the MVC dispatch, after the security filter chain has handed the request over - so the
 * application's {@code AccessDeniedHandler} and {@code AuthenticationEntryPoint} never see them, and
 * without this they surface as a 500. That one detail is why almost every service ends up with a
 * hand-written advice for these two types.
 *
 * <p>Neither answer says anything about why. Distinguishing "you lack the editor role" from "that
 * record belongs to another tenant" turns an endpoint into an oracle for enumerating which records
 * exist; the specifics belong in the audit log, keyed by subject, where support can find them and a
 * caller cannot.
 *
 * <p>Registered at {@link #DEFAULT_MODULE_ORDER}, so a security module that wants these rendered
 * under its own codes - matching what its filter-chain handlers emit for the same conditions - can
 * contribute a mapper at a lower order and win.
 */
public class SecurityProblemMapper implements ExceptionProblemMapper {

    @Override
    public boolean supports(Throwable exception) {
        return exception instanceof AccessDeniedException || exception instanceof AuthenticationException;
    }

    @Override
    public ProblemDefinition map(Throwable exception) {
        return exception instanceof AccessDeniedException
                ? ProblemDefinition.of(ProblemStatus.FORBIDDEN, ProblemCodes.FORBIDDEN)
                : ProblemDefinition.of(ProblemStatus.UNAUTHORIZED, ProblemCodes.UNAUTHORIZED);
    }

    @Override
    public int getOrder() {
        return DEFAULT_MODULE_ORDER;
    }
}
