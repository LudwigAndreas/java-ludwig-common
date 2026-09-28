package ru.ludwigandreas.restclient.config;

/**
 * How much of an exchange a named client writes to the log.
 *
 * <p>The levels are cumulative and deliberately ordered so that raising one step at a time is a
 * safe operational move: nothing, then the line every operator wants, then the headers, then - only
 * on purpose, size-capped and redacted - the payload.
 */
public enum LogDetail {

    /** No exchange logging. Metrics and traces still record the call. */
    NONE,

    /** One line per call: method, templated URI, status, duration, attempt count. */
    BASIC,

    /** {@link #BASIC} plus request and response headers, with the redaction list applied. */
    HEADERS,

    /**
     * {@link #HEADERS} plus bodies, truncated to {@code logging.max-body-size} and redacted.
     *
     * <p>Opt-in and never inherited by accident: a body is where the personal data is, and a log
     * pipeline is a much wider audience than the service itself. A streaming response is never
     * consumed to satisfy this - see {@code ExchangeLogger}.
     */
    BODY;

    /** Whether this level includes everything {@code other} includes. */
    public boolean includes(LogDetail other) {
        return ordinal() >= other.ordinal();
    }
}
