package ru.ludwigandreas.grpc.client.error;

import io.grpc.Status;

/**
 * A call failed because no connection could be made, or it broke before the peer answered.
 */
public class GrpcClientConnectionException extends GrpcClientException {

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
    public GrpcClientConnectionException(String clientName, String correlationId, Status.Code code, String message,
            Throwable cause) {
        super(clientName, correlationId, code, message, cause);
    }
}
