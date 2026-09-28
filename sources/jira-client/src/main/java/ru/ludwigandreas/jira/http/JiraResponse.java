package ru.ludwigandreas.jira.http;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One HTTP response: status, headers and the body as bytes.
 *
 * <p>Header lookup is case-insensitive, because HTTP header names are and because Jira, Tomcat and whatever
 * reverse proxy sits in front of them do not agree on capitalization.
 */
public final class JiraResponse {

    private static final int LOWEST_SUCCESS_STATUS = 200;
    private static final int LOWEST_REDIRECT_STATUS = 300;

    private final int status;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    /**
     * Creates a response, copying the headers into a case-insensitive map and the body defensively.
     *
     * @param status HTTP status code
     * @param headers response headers; copied into a case-insensitive map
     * @param body response body, never {@code null} - use an empty array for no content
     */
    public JiraResponse(int status, Map<String, List<String>> headers, byte[] body) {
        this.status = status;
        // Deliberately NOT Map.copyOf: that returns a HashMap, which would silently throw away the
        // case-insensitive comparator and make header("Retry-After") miss a "retry-after" from a proxy.
        Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        this.headers = Collections.unmodifiableMap(copy);
        this.body = body.clone();
    }

    /** HTTP status code. */
    public int status() {
        return status;
    }

    /** True for 2xx. */
    public boolean isSuccessful() {
        return status >= LOWEST_SUCCESS_STATUS && status < LOWEST_REDIRECT_STATUS;
    }

    /** Response body bytes. */
    public byte[] body() {
        return body.clone();
    }

    /** Response body decoded as UTF-8. Jira always answers in UTF-8 regardless of the request. */
    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** True when the response carried no body, which Jira uses for 204 on updates and deletes. */
    public boolean hasBody() {
        return body.length > 0;
    }

    /** First value of a header, matched case-insensitively. */
    public Optional<String> header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    /** All response headers, keyed case-insensitively. */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /**
     * The server's {@code Retry-After}, in both of its RFC 9110 forms: delay-seconds, and an HTTP-date.
     *
     * <p>A date in the past yields {@link Duration#ZERO} rather than a negative duration, so a caller can
     * hand the result straight to a sleep without guarding it. An unparseable value yields an empty
     * optional rather than an exception: a malformed header from a proxy must not turn a retryable 503 into
     * a hard failure.
     */
    public Optional<Duration> retryAfter() {
        Optional<String> raw = header("Retry-After");
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        String value = raw.get().trim();
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
        } catch (NumberFormatException notASecondCount) {
            return parseHttpDate(value);
        }
    }

    private static Optional<Duration> parseHttpDate(String value) {
        try {
            OffsetDateTime when = OffsetDateTime.parse(value,
                    DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ROOT));
            Duration delay = Duration.between(OffsetDateTime.now(ZoneOffset.UTC), when);
            return Optional.of(delay.isNegative() ? Duration.ZERO : delay);
        } catch (DateTimeParseException unparseable) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return "HTTP " + status + " (" + body.length + " bytes)";
    }
}
