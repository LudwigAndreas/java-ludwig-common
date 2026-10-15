package ru.ludwigandreas.grpc.server.error;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Every handler thread is busy and the queue is full; rendered as {@code RESOURCE_EXHAUSTED}.
 */
public class GrpcServerSaturatedException extends LocalizedException {

    /**
     * The problem code.
     */
    public static final String CODE = "ludwig.grpc.server.error.saturated";

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     */
    public GrpcServerSaturatedException() {
        super(ProblemStatus.TOO_MANY_REQUESTS, CODE);
    }
}
