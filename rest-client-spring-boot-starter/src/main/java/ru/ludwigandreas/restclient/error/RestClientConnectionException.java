package ru.ludwigandreas.restclient.error;

/**
 * The request never reached the peer: DNS failure, refused connection, reset socket, TLS handshake
 * rejection.
 *
 * <p>The one failure class that is always safe to retry regardless of method, because nothing was
 * processed. The retry policy treats it that way, and it is the only exception to
 * {@code idempotent-methods-only} the starter makes on its own.
 */
public class RestClientConnectionException extends RestClientException {

    private static final long serialVersionUID = 1L;

    public RestClientConnectionException(String clientName, String correlationId, String message,
                                         Throwable cause) {
        super(clientName, correlationId, message, cause);
    }
}
