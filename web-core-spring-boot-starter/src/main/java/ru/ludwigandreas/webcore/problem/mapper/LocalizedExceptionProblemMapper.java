package ru.ludwigandreas.webcore.problem.mapper;

import org.springframework.core.Ordered;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;

/**
 * Renders a {@link LocalizedException}, which already carries its own definition.
 *
 * <p>Registered as a mapper rather than handled by a dedicated {@code @ExceptionHandler} so that it
 * also benefits from the registry's cause-chain walk: a business exception wrapped by a transaction
 * manager or a proxy is still answered as the 409 or 422 the domain intended, instead of turning
 * into a 500 because a framework layer decided to wrap it on the way out.
 *
 * <p>Highest precedence: when a service has deliberately said what a failure means, nothing else
 * gets to reinterpret it.
 */
public class LocalizedExceptionProblemMapper implements ExceptionProblemMapper {

    @Override
    public boolean supports(Throwable exception) {
        return exception instanceof LocalizedException;
    }

    @Override
    public ProblemDefinition map(Throwable exception) {
        return ((LocalizedException) exception).toDefinition();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
