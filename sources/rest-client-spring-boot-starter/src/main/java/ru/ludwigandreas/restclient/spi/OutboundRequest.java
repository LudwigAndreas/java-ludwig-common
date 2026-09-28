package ru.ludwigandreas.restclient.spi;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * An outgoing request, as a listener, a translator or an audit sink sees it.
 *
 * <p>Immutable, and deliberately not the live request: a listener that could mutate headers would be
 * an interceptor, and the pipeline could no longer state what the request was when it went out.
 *
 * @param clientName  the named client this call belongs to
 * @param method      the HTTP method
 * @param uri         the fully expanded target URI
 * @param uriTemplate the URI <em>template</em> - {@code /invoices/{id}} - when one is known, else
 *                    the path. This is what may be used as a metric tag or an audit field; the
 *                    expanded URI must not be, because a caller-supplied path id is unbounded
 *                    cardinality in a metric and an identifier in a retained record
 * @param headers     request headers, already redacted
 * @param attempt     which attempt this is, counting from 1
 */
public record OutboundRequest(
        String clientName,
        String method,
        URI uri,
        String uriTemplate,
        Map<String, List<String>> headers,
        int attempt) {

    public OutboundRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
