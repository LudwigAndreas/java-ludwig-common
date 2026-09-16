package ru.ludwigandreas.security.web;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Renders a 401 or 403 raised inside the MVC dispatch under this module's own codes.
 *
 * <p>The web-core starter already maps both exception types, so this mapper is not about coverage -
 * it is about the codes matching. {@link ProblemDetailAuthenticationEntryPoint} and
 * {@link ProblemDetailAccessDeniedHandler} answer with {@code ludwig.security.error.unauthorized} and
 * {@code ludwig.security.error.forbidden} when the filter chain rejects a request; without this
 * mapper, the identical rejection arriving from a {@code @PreAuthorize} or a data guard - which
 * throws after the filter chain has handed over, where those handlers can no longer see it - would
 * come back under a different code. A client checking {@code code} would then have to know which
 * layer denied it, which is precisely the internal detail it should not have to know.
 *
 * <p>Registered at a lower order than web-core's own mapper so it wins, and only when that starter
 * is on the classpath.
 */
public class SecurityProblemMapper implements ExceptionProblemMapper {

    /** Ahead of web-core's generic mapper for the same two types - the lower order wins. */
    private static final int ORDER = DEFAULT_MODULE_ORDER - 100;

    private static final String UNAUTHORIZED = "ludwig.security.error.unauthorized";
    private static final String FORBIDDEN = "ludwig.security.error.forbidden";

    @Override
    public boolean supports(Throwable exception) {
        return exception instanceof AccessDeniedException || exception instanceof AuthenticationException;
    }

    @Override
    public ProblemDefinition map(Throwable exception) {
        // Says only that access was denied. Which role was missing, which scope excluded the row,
        // which partner the certificate mapped to - all of that is in the audit log, keyed by
        // subject, where support can find it and a caller cannot use it to map the permission model.
        return exception instanceof AccessDeniedException
                ? ProblemDefinition.of(ProblemStatus.FORBIDDEN, FORBIDDEN)
                : ProblemDefinition.of(ProblemStatus.UNAUTHORIZED, UNAUTHORIZED);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
