package ru.ludwigandreas.restclient.spi;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The answer to an {@link OutboundRequest}, as a listener or audit sink sees it.
 *
 * @param statusCode the HTTP status
 * @param headers    response headers, already redacted
 * @param duration   wall time of this attempt, not of the logical call
 * @param bodySnippet the first bytes of the body as text, present only when the client logs at
 *                    {@code BODY} level or the response failed; {@code null} otherwise, because
 *                    materializing a body nobody asked for would break streaming responses
 */
public record OutboundResponse(
        int statusCode,
        Map<String, List<String>> headers,
        Duration duration,
        String bodySnippet) {

    public OutboundResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** Whether the status is 2xx. */
    public boolean successful() {
        // CHECKSTYLE.OFF: MagicNumber - the 2xx range is the definition, not a tunable.
        return statusCode >= 200 && statusCode < 300;
        // CHECKSTYLE.ON: MagicNumber
    }
}
