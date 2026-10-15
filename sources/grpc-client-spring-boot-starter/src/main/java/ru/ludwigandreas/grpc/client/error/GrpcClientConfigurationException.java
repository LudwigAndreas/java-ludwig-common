package ru.ludwigandreas.grpc.client.error;

/**
 * The client configuration was refused. The message lists every problem found, not the first.
 */
public class GrpcClientConfigurationException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message every problem found
     */
    public GrpcClientConfigurationException(String message) {
        super(message);
    }
}
