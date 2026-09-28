package ru.ludwigandreas.restclient.error;

import lombok.Getter;

/**
 * Root of every failure this starter reports.
 *
 * <p>Deliberately not Spring's {@code org.springframework.web.client.RestClientException}, and not a
 * subclass of it. Spring's hierarchy answers "what did the HTTP layer do"; this one answers "which of
 * this service's dependencies failed, and how" - which is the question an operator, an alert and a
 * {@code catch} block all actually ask. A service that wants to treat billing being down differently
 * from pricing being down cannot do it by catching {@code HttpServerErrorException}, because that
 * type does not know which client threw it.
 *
 * <p>Every instance carries the client name and the correlation id. The client name makes the
 * message self-explanatory in a log line that has been detached from its stack trace; the correlation
 * id is what joins this service's failure to the record of the call on the other side.
 */
@Getter
public class RestClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The named client whose call failed. */
    private final String clientName;

    /** The platform correlation id of the unit of work that made the call, or {@code null}. */
    private final String correlationId;

    /** Creates the exception with no underlying cause. */
    public RestClientException(String clientName, String correlationId, String message) {
        this(clientName, correlationId, message, null);
    }

    /** Creates the exception, prefixing {@code message} with the client and the correlation id. */
    public RestClientException(String clientName, String correlationId, String message, Throwable cause) {
        super(decorate(clientName, correlationId, message), cause);
        this.clientName = clientName;
        this.correlationId = correlationId;
    }

    /**
     * Puts the client and the correlation id into the message itself.
     *
     * <p>Not only into fields: the message is what a log appender writes, what an alert quotes and
     * what an exception-tracking tool groups on, and every one of those loses the fields.
     */
    private static String decorate(String clientName, String correlationId, String message) {
        StringBuilder sb = new StringBuilder("[client=").append(clientName);
        if (correlationId != null && !correlationId.isBlank()) {
            sb.append(", correlationId=").append(correlationId);
        }
        return sb.append("] ").append(message).toString();
    }
}
