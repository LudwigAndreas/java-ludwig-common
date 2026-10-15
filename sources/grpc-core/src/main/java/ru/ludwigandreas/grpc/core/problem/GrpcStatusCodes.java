package ru.ludwigandreas.grpc.core.problem;

import io.grpc.Status;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The one table between the platform's problem statuses and gRPC status codes.
 *
 * <p>A gRPC code is the coarse signal - what a load balancer, a retry policy and a circuit breaker
 * act on. The precise answer is the problem code, which travels beside it in the status details
 * (see {@link GrpcStatusCodec}); nothing is lost by several problem statuses sharing a gRPC code,
 * exactly as nothing was lost by several problems sharing HTTP 409.
 *
 * <h2>Why the forward table has no {@code default} branch</h2>
 *
 * <p>{@link #forProblemStatus} is a {@code switch} expression over the enum with every constant
 * named. A status added to {@code web-core} therefore fails this module's <em>compilation</em>
 * until someone decides its gRPC code, instead of falling through to {@code UNKNOWN} and being
 * discovered by a caller's circuit breaker. {@code GrpcStatusCodesTest} states the same rule as a
 * test, so the intent survives a refactoring that reintroduces a default.
 */
public final class GrpcStatusCodes {

    private GrpcStatusCodes() {
    }

    /**
     * The gRPC code a problem status is rendered as.
     *
     * @param status a platform problem status
     * @return its gRPC code, never {@code OK} and never {@code UNKNOWN}
     */
    public static Status.Code forProblemStatus(ProblemStatus status) {
        return switch (status) {
            // The request itself is wrong and repeating it unchanged will not help.
            case INVALID, METHOD_NOT_ALLOWED, NOT_ACCEPTABLE, UNSUPPORTED_MEDIA_TYPE -> Status.Code.INVALID_ARGUMENT;
            case UNAUTHORIZED -> Status.Code.UNAUTHENTICATED;
            case FORBIDDEN -> Status.Code.PERMISSION_DENIED;
            // GONE is NOT_FOUND with a memory: the caller's next step is the same.
            case NOT_FOUND, GONE -> Status.Code.NOT_FOUND;
            // ABORTED rather than ALREADY_EXISTS: a 409 here is as often an optimistic-lock failure
            // as a duplicate, ABORTED is the code gRPC defines for "retry at a higher level", and
            // the problem code in the details says which of the two it was.
            case CONFLICT, LOCKED -> Status.Code.ABORTED;
            // The request is well-formed and the system is not in the state it needs.
            case PRECONDITION_FAILED, UNPROCESSABLE -> Status.Code.FAILED_PRECONDITION;
            case PAYLOAD_TOO_LARGE, TOO_MANY_REQUESTS -> Status.Code.RESOURCE_EXHAUSTED;
            case INTERNAL -> Status.Code.INTERNAL;
            case NOT_IMPLEMENTED -> Status.Code.UNIMPLEMENTED;
            // A dependency of the server failed. UNAVAILABLE is what a caller's retry policy and
            // breaker are written against, which is the behaviour a 502/503 asks for.
            case BAD_GATEWAY, SERVICE_UNAVAILABLE -> Status.Code.UNAVAILABLE;
            case GATEWAY_TIMEOUT -> Status.Code.DEADLINE_EXCEEDED;
        };
    }

    /**
     * The problem status to assume for a gRPC code that arrived with no problem attached - a
     * failure raised by the transport, or by a server that is not built on this platform.
     *
     * <p>Lossy by nature and used only as a fallback: a status produced by a platform server
     * carries its original problem status in the details and never reaches this method.
     *
     * @param code a gRPC status code other than {@code OK}
     * @return the nearest problem status
     */
    public static ProblemStatus toProblemStatus(Status.Code code) {
        return switch (code) {
            case INVALID_ARGUMENT, OUT_OF_RANGE -> ProblemStatus.INVALID;
            case UNAUTHENTICATED -> ProblemStatus.UNAUTHORIZED;
            case PERMISSION_DENIED -> ProblemStatus.FORBIDDEN;
            case NOT_FOUND -> ProblemStatus.NOT_FOUND;
            case ALREADY_EXISTS, ABORTED -> ProblemStatus.CONFLICT;
            case FAILED_PRECONDITION -> ProblemStatus.UNPROCESSABLE;
            case RESOURCE_EXHAUSTED -> ProblemStatus.TOO_MANY_REQUESTS;
            case UNIMPLEMENTED -> ProblemStatus.NOT_IMPLEMENTED;
            case UNAVAILABLE -> ProblemStatus.SERVICE_UNAVAILABLE;
            case DEADLINE_EXCEEDED -> ProblemStatus.GATEWAY_TIMEOUT;
            case OK, CANCELLED, UNKNOWN, INTERNAL, DATA_LOSS -> ProblemStatus.INTERNAL;
        };
    }
}
