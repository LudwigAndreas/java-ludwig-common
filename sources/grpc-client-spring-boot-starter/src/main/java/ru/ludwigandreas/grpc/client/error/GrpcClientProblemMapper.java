package ru.ludwigandreas.grpc.client.error;

import io.grpc.Status;
import java.util.Set;
import org.springframework.core.Ordered;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemCodes;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Renders a client failure that escapes a controller, by {@code rest-client}'s reasoning.
 *
 * <p>A dependency that is down, slow, unreachable or refused by our own breaker is
 * {@code upstream-unavailable} (503). A peer that asks us to back off is 429. And a peer that says
 * <em>we</em> sent it something wrong - {@code INVALID_ARGUMENT}, {@code NOT_FOUND},
 * {@code FAILED_PRECONDITION} and the rest - is a 500: this service built that request, and
 * passing the peer's 4xx through would blame our own caller for it. An authentication failure is
 * ours too, and is 500 for the same reason.
 */
public class GrpcClientProblemMapper implements ExceptionProblemMapper {

    private static final int ORDER_MARGIN = 100;

    private static final Set<Status.Code> CALLER_SIDE = Set.of(
            Status.Code.INVALID_ARGUMENT, Status.Code.NOT_FOUND, Status.Code.ALREADY_EXISTS,
            Status.Code.FAILED_PRECONDITION, Status.Code.OUT_OF_RANGE, Status.Code.PERMISSION_DENIED,
            Status.Code.UNAUTHENTICATED, Status.Code.UNIMPLEMENTED, Status.Code.ABORTED, Status.Code.CANCELLED);

    @Override
    public boolean supports(Throwable exception) {
        return exception instanceof GrpcClientException;
    }

    @Override
    public ProblemDefinition map(Throwable exception) {
        if (exception instanceof GrpcClientAuthenticationException) {
            return ProblemDefinition.of(ProblemStatus.INTERNAL, ProblemCodes.INTERNAL);
        }
        if (exception instanceof GrpcClientResponseException response) {
            if (response.peerCode() == Status.Code.RESOURCE_EXHAUSTED) {
                return ProblemDefinition.of(ProblemStatus.TOO_MANY_REQUESTS, ProblemCodes.TOO_MANY_REQUESTS);
            }
            if (CALLER_SIDE.contains(response.peerCode())) {
                return ProblemDefinition.of(ProblemStatus.INTERNAL, ProblemCodes.INTERNAL);
            }
        }
        return ProblemDefinition.of(ProblemStatus.SERVICE_UNAVAILABLE, ProblemCodes.UPSTREAM_UNAVAILABLE);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - ORDER_MARGIN;
    }
}
