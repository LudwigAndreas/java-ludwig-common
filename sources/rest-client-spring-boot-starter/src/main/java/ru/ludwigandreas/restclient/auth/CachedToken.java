package ru.ludwigandreas.restclient.auth;

import java.time.Duration;
import java.time.Instant;

/**
 * A token and the instant it stops being usable.
 *
 * @param value    the token; never logged, never put in a metric tag, never in an audit record
 * @param expiresAt when the issuer says it expires, or {@code null} for a token with no stated
 *                  lifetime - which is treated as "expires immediately" rather than "never expires",
 *                  because the second reading is how a revoked token gets used for weeks
 */
public record CachedToken(String value, Instant expiresAt) {

    /**
     * Whether this token is still usable {@code skew} before its stated expiry.
     *
     * <p>The skew is not politeness: the token has to survive the call it is about to be used for,
     * and a token that expires 200ms from now will be rejected by a peer 300ms into the request.
     * Without it the failure is an intermittent 401 with no pattern, on a token that was valid when
     * it was read.
     */
    public boolean usableAt(Instant now, Duration skew) {
        return value != null && !value.isBlank()
                && expiresAt != null && now.plus(skew).isBefore(expiresAt);
    }
}
