package ru.ludwigandreas.restclient.spi;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Everything a {@link ResponseErrorTranslator} may look at when deciding what a failed response
 * means.
 *
 * @param clientName    the named client
 * @param method        the HTTP method
 * @param uri           the expanded target URI
 * @param uriTemplate   the URI template when known
 * @param statusCode    the HTTP status
 * @param reasonPhrase  the status reason, when the peer sent one
 * @param contentType   the response {@code Content-Type}, lower-cased, or {@code null}
 * @param headers       response headers, already redacted
 * @param body          the response body as text, truncated to the client's
 *                      {@code logging.max-body-size}. Always materialized for a failed response -
 *                      an error body is small by construction and is the only thing that explains
 *                      the failure - and never for a successful one
 * @param correlationId the platform correlation id of the unit of work that made the call
 */
public record ErrorContext(
        String clientName,
        String method,
        URI uri,
        String uriTemplate,
        int statusCode,
        String reasonPhrase,
        String contentType,
        Map<String, List<String>> headers,
        String body,
        String correlationId) {

    public ErrorContext {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
