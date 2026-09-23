package ru.ludwigandreas.restclient.error;

import java.time.Duration;
import lombok.Getter;

/**
 * The call did not finish inside its deadline.
 *
 * <p>Separate from {@link RestClientConnectionException} because the two say opposite things about
 * whether the request was processed. A connection failure means the bytes never arrived; a timeout
 * means they may well have, and the peer may have done the work - which is exactly why a timed-out
 * POST is not retried unless the caller opts in.
 */
@Getter
public class RestClientTimeoutException extends RestClientException {

    private static final long serialVersionUID = 1L;

    /** The deadline that was exceeded. */
    private final transient Duration timeout;

    /** Which deadline: {@code connect}, {@code read}, {@code request} or {@code time-limiter}. */
    private final String kind;
    /** Creates the exception, naming which of the client's deadlines was exceeded. */
    public RestClientTimeoutException(String clientName, String correlationId, String message,
                                      String kind, Duration timeout, Throwable cause) {
        super(clientName, correlationId, message, cause);
        this.kind = kind;
        this.timeout = timeout;
    }
}
