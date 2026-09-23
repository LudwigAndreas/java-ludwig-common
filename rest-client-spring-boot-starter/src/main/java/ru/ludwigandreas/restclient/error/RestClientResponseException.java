package ru.ludwigandreas.restclient.error;

import java.util.List;
import java.util.Map;
import lombok.Getter;
import org.springframework.http.ProblemDetail;

/**
 * The peer answered, and the answer was a failure status.
 *
 * <p>Distinct from every other subclass because it is the only one where the dependency is healthy
 * and responding. That distinction is the one that matters operationally: a 422 is this service
 * sending something wrong, a 503 is the dependency in trouble, and a
 * {@link RestClientConnectionException} is the network - three different people fix them.
 */
@Getter
public class RestClientResponseException extends RestClientException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String reasonPhrase;

    /** Response headers, already redacted; safe to log in full. */
    private final transient Map<String, List<String>> headers;

    /**
     * The first bytes of the body, truncated to the client's {@code logging.max-body-size}.
     *
     * <p>Present on every failed response, whatever the logging level, because an error body is
     * small by construction and is usually the only thing that explains the status. Redacted with
     * the client's field list first - a 400 from an auth endpoint routinely echoes the credential
     * back.
     */
    private final String bodySnippet;

    /**
     * The parsed {@code application/problem+json} document, or {@code null} when the peer sent
     * something else.
     *
     * <p>Transient: a {@code ProblemDetail} carries arbitrary extension members, which have no
     * serialization contract. The status and the body snippet survive a round trip; the structured
     * form does not have to.
     */
    private final transient ProblemDetail problemDetail;

    /** Creates the exception from a failed response whose headers and body are already redacted. */
    // CHECKSTYLE.OFF: ParameterNumber - an exception that reports a failed HTTP response has to
    // carry the whole response; assembling it from setters would make it mutable, which the style
    // rules rightly forbid for exceptions.
    public RestClientResponseException(String clientName, String correlationId, String message,
                                       int statusCode, String reasonPhrase,
                                       Map<String, List<String>> headers, String bodySnippet,
                                       ProblemDetail problemDetail) {
        super(clientName, correlationId, message);
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.bodySnippet = bodySnippet;
        this.problemDetail = problemDetail;
    }
    // CHECKSTYLE.ON: ParameterNumber

    /** Whether the status is 4xx - i.e. this service sent something the peer rejected. */
    public boolean clientError() {
        // CHECKSTYLE.OFF: MagicNumber - the 4xx range is the definition, not a tunable.
        return statusCode >= 400 && statusCode < 500;
        // CHECKSTYLE.ON: MagicNumber
    }

    /** Whether the status is 5xx - i.e. the peer failed. */
    public boolean serverError() {
        // CHECKSTYLE.OFF: MagicNumber - as above.
        return statusCode >= 500 && statusCode < 600;
        // CHECKSTYLE.ON: MagicNumber
    }
}
