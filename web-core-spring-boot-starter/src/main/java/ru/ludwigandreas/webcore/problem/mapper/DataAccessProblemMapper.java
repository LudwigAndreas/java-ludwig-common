package ru.ludwigandreas.webcore.problem.mapper;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemCodes;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Turns Spring's data-access exceptions into the answers they actually deserve.
 *
 * <p>All four of these default to a 500 otherwise, and three of them are not this service's fault:
 *
 * <ul>
 *   <li>a unique or foreign-key constraint firing is a conflict with the resource's current state -
 *       including the concurrent-duplicate case an idempotency index is there to catch;
 *   <li>losing an optimistic-locking race is the same 409 a client gets for sending a stale version,
 *       because from the client's side it is the same situation;
 *   <li>a row lock that could not be taken is a 423, and retryable;
 *   <li>a query timeout is a 503 with the fault named as ours, rather than a bare 500.
 * </ul>
 *
 * <p>Mapped here, once, because the alternative is every service re-deriving these four - and the
 * usual outcome of that is the first two being answered as 500s until someone reads an incident
 * report.
 */
public class DataAccessProblemMapper implements ExceptionProblemMapper {

    @Override
    public boolean supports(Throwable exception) {
        return exception instanceof OptimisticLockingFailureException
                || exception instanceof DataIntegrityViolationException
                || exception instanceof PessimisticLockingFailureException
                || exception instanceof QueryTimeoutException;
    }

    @Override
    public ProblemDefinition map(Throwable exception) {
        if (exception instanceof OptimisticLockingFailureException) {
            return ProblemDefinition.of(ProblemStatus.CONFLICT, ProblemCodes.CONCURRENT_MODIFICATION);
        }
        if (exception instanceof DataIntegrityViolationException) {
            return ProblemDefinition.of(ProblemStatus.CONFLICT, ProblemCodes.CONFLICT);
        }
        if (exception instanceof PessimisticLockingFailureException) {
            return ProblemDefinition.of(ProblemStatus.LOCKED, ProblemCodes.CONCURRENT_MODIFICATION);
        }
        return ProblemDefinition.of(ProblemStatus.SERVICE_UNAVAILABLE, ProblemCodes.UPSTREAM_UNAVAILABLE);
    }

    @Override
    public int getOrder() {
        return DEFAULT_MODULE_ORDER;
    }
}
