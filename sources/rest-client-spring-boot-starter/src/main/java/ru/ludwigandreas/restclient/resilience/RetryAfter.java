package ru.ludwigandreas.restclient.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;

/**
 * Parses a {@code Retry-After} header into a wait, in either of the two forms RFC 9110 allows.
 *
 * <p>Honouring it is not politeness. A server answering 429 or 503 has stated when it expects to be
 * able to serve again; retrying after the client's own 200ms backoff instead means every rejected
 * caller comes back immediately, which is how a rate limit becomes an outage and how a service that
 * is restarting never finishes restarting.
 *
 * <p>The value is capped by {@code retry.max-retry-after}. Without a cap, a peer that is broken or
 * hostile can park a request thread for an hour with one header - and a thread parked for an hour
 * is indistinguishable, from the outside, from a deadlock.
 */
public final class RetryAfter {

    private static final long MILLIS_PER_SECOND = 1000L;

    private RetryAfter() {
    }

    /**
     * The wait {@code headerValue} asks for, clamped to {@code [0, max]}, or {@code -1} when the
     * header is absent or unparseable.
     *
     * <p>Returning {@code -1} rather than zero: "the peer said nothing" and "the peer said retry
     * immediately" are different, and only the first should fall back to the computed backoff.
     */
    public static long millis(String headerValue, Duration max, Clock clock) {
        if (headerValue == null || headerValue.isBlank()) {
            return -1;
        }
        String value = headerValue.trim();
        long millis = parseSeconds(value);
        if (millis < 0) {
            millis = parseHttpDate(value, clock);
        }
        if (millis < 0) {
            return -1;
        }
        return Math.min(millis, max.toMillis());
    }

    private static long parseSeconds(String value) {
        try {
            long seconds = Long.parseLong(value);
            // A negative delta-seconds is a malformed header, not an instruction to retry in the
            // past; clamping it to zero would make a broken peer look like a fast one.
            return seconds < 0 ? -1 : seconds * MILLIS_PER_SECOND;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static long parseHttpDate(String value, Clock clock) {
        try {
            ZonedDateTime target = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            long delta = Duration.between(clock.instant(), target.toInstant()).toMillis();
            // A date already in the past means "now" - the peer's clock and ours disagree, which is
            // ordinary, and the useful reading is that the wait is over.
            return Math.max(delta, 0);
        } catch (DateTimeParseException ex) {
            return -1;
        }
    }
}
