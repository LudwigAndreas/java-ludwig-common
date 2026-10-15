package ru.ludwigandreas.grpc.client.error;

import io.grpc.Status;

/**
 * A call through a named client failed. The subtype says how; the message names the client and the
 * correlation id, so a log line is attributable without a stack trace.
 *
 * <p>A generated stub throws {@code StatusRuntimeException}, and that does not change: these are
 * its <em>cause</em>. Read one with {@link #from(Throwable)}. {@code web-core}'s problem registry
 * walks the cause chain, so one escaping a controller is rendered by this module's mapper with no
 * code at the call site.
 */
public abstract class GrpcClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String clientName;
    private final String correlationId;
    private final Status.Code code;

    protected GrpcClientException(String clientName, String correlationId, Status.Code code, String message,
                                  Throwable cause) {
        super("gRPC client '" + clientName + "'" + (correlationId == null ? "" : " [" + correlationId + "]")
                + ": " + message, cause);
        this.clientName = clientName;
        this.correlationId = correlationId;
        this.code = code;
    }

    /**
     * The typed failure behind a stub's exception.
     *
     * @param failure what a stub threw
     * @return the client failure in its cause chain, or {@code null} when there is none
     */
    public static GrpcClientException from(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof GrpcClientException typed) {
                return typed;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    /**
     * The client the call was made through.
     *
     * @return the client name
     */
    public String clientName() {
        return clientName;
    }

    /**
     * The correlation id the call was made under.
     *
     * @return the id, or {@code null}
     */
    public String correlationId() {
        return correlationId;
    }

    /**
     * The gRPC code the call ended with.
     *
     * @return the code
     */
    public Status.Code code() {
        return code;
    }
}
