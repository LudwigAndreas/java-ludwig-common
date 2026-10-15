package ru.ludwigandreas.grpc.server.error;

/**
 * The gRPC server refused its configuration. The message lists every problem found, not the first.
 */
public class GrpcServerConfigurationException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message every problem found
     */
    public GrpcServerConfigurationException(String message) {
        super(message);
    }
}
