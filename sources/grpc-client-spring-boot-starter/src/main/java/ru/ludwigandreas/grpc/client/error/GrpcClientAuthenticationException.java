package ru.ludwigandreas.grpc.client.error;

import io.grpc.Status;

/**
 * A call failed because a credential could not be obtained, was missing where one is required, or was rejected by the
 * peer.
 */
public class GrpcClientAuthenticationException extends GrpcClientException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param clientName    the client
     * @param correlationId the correlation id, or {@code null}
     * @param code          the code the call ended with
     * @param message       what happened
     * @param cause         the underlying failure, or {@code null}
     */
    public GrpcClientAuthenticationException(String clientName, String correlationId, Status.Code code, String message,
            Throwable cause) {
        super(clientName, correlationId, code, message, cause);
    }
}
