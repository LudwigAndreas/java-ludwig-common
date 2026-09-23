package ru.ludwigandreas.restclient.error;

import org.springframework.core.Ordered;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemCodes;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Renders a dependency failure that escaped a controller as the platform's own problem document.
 *
 * <p>Registered only when {@code web-core-spring-boot-starter} is on the classpath. It is the reason
 * this module defines no problem codes and no {@code @RestControllerAdvice} of its own: web-core
 * already owns how a failure becomes HTTP, and a second renderer would produce documents that are
 * almost but not quite the same shape.
 *
 * <p>The mapping is deliberately conservative about what it tells the caller.
 *
 * <ul>
 *   <li>A 5xx, a timeout, a connection failure or a refused call becomes
 *       {@code UPSTREAM_UNAVAILABLE} (503). The caller can retry; it is told nothing about which
 *       dependency failed, because the name of this service's dependencies is not its callers'
 *       business.</li>
 *   <li>A 429 from upstream becomes a 429, so a client that already backs off on rate limiting
 *       keeps doing the right thing.</li>
 *   <li>A 4xx from upstream becomes {@code INTERNAL} (500). This is the case people get wrong: a
 *       422 from billing means <em>this</em> service sent billing something invalid, so passing the
 *       422 through would blame the caller for a defect it did not cause and could not fix.</li>
 *   <li>An authentication failure becomes {@code INTERNAL} too - this service's credentials are
 *       misconfigured, which is never the caller's problem to solve.</li>
 * </ul>
 */
public class RestClientProblemMapper implements ExceptionProblemMapper {

    private static final int TOO_MANY_REQUESTS = 429;

    /** How far ahead of {@link Ordered#LOWEST_PRECEDENCE} this mapper runs; see {@link #getOrder()}. */
    private static final int ORDER_MARGIN = 100;

    @Override
    public boolean supports(Throwable exception) {
        return exception instanceof RestClientException;
    }

    @Override
    public ProblemDefinition map(Throwable exception) {
        if (exception instanceof RestClientResponseException response) {
            return mapResponse(response);
        }
        if (exception instanceof RestClientAuthenticationException) {
            return ProblemDefinition.of(ProblemStatus.INTERNAL, ProblemCodes.INTERNAL);
        }
        // Timeout, connection failure, refused-by-policy: all of them mean "this request depends on
        // something that did not answer", which is exactly what upstream-unavailable says.
        return ProblemDefinition.of(ProblemStatus.SERVICE_UNAVAILABLE, ProblemCodes.UPSTREAM_UNAVAILABLE);
    }

    private ProblemDefinition mapResponse(RestClientResponseException response) {
        if (response.getStatusCode() == TOO_MANY_REQUESTS) {
            return ProblemDefinition.of(ProblemStatus.TOO_MANY_REQUESTS, ProblemCodes.TOO_MANY_REQUESTS);
        }
        if (response.serverError()) {
            return ProblemDefinition.of(ProblemStatus.SERVICE_UNAVAILABLE, ProblemCodes.UPSTREAM_UNAVAILABLE);
        }
        return ProblemDefinition.of(ProblemStatus.INTERNAL, ProblemCodes.INTERNAL);
    }

    /**
     * Ahead of the default order so it wins over a generic mapper, and well behind 0 so a service
     * that wants to expose an upstream 404 as its own 404 simply registers a mapper at the default
     * order.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - ORDER_MARGIN;
    }
}
