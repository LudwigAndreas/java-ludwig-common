package ru.ludwigandreas.grpc.client.error;

import io.grpc.Status;
import ru.ludwigandreas.grpc.core.problem.DecodedProblem;

/**
 * A call failed because the peer answered with a failure. {@link #problem()} is the decoded problem: exact when the
 * peer is a platform server, approximated from the code when it is not.
 */
public class GrpcClientResponseException extends GrpcClientException {

    private static final long serialVersionUID = 1L;

    private final transient DecodedProblem problem;

    /**
     * Creates the exception.
     *
     * @param clientName    the client
     * @param correlationId the correlation id, or {@code null}
     * @param problem       the decoded failure
     * @param cause         the status exception
     */
    public GrpcClientResponseException(String clientName, String correlationId, DecodedProblem problem,
                                       Throwable cause) {
        super(clientName, correlationId, problem.code(),
                "the peer answered " + problem.code()
                        + problem.problemCode().map(code -> " (" + code + ")").orElse(""),
                cause);
        this.problem = problem;
    }

    /**
     * The peer's answer in the platform's vocabulary.
     *
     * @return the decoded problem
     */
    public DecodedProblem problem() {
        return problem;
    }

    /**
     * The code the peer answered with.
     *
     * @return the code
     */
    public Status.Code peerCode() {
        return problem.code();
    }
}
