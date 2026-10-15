package ru.ludwigandreas.grpc.client.error;

import io.grpc.Status;

/**
 * This service's own policy refused the call before anything was sent: the rate limiter had no
 * permit, the bulkhead was full or the circuit breaker was open. The peer never saw it.
 */
public class GrpcClientCallNotPermittedException extends GrpcClientException {

    private static final long serialVersionUID = 1L;

    private final String policy;

    /**
     * Creates the exception.
     *
     * @param clientName    the client
     * @param correlationId the correlation id, or {@code null}
     * @param policy        {@code rate-limiter}, {@code bulkhead} or {@code circuit-breaker}
     */
    public GrpcClientCallNotPermittedException(String clientName, String correlationId, String policy) {
        super(clientName, correlationId, Status.Code.UNAVAILABLE, "refused by this client's " + policy, null);
        this.policy = policy;
    }

    /**
     * The policy that refused.
     *
     * @return {@code rate-limiter}, {@code bulkhead} or {@code circuit-breaker}
     */
    public String policy() {
        return policy;
    }
}
