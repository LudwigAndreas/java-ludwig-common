package ru.ludwigandreas.restclient.config;

import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** Exchange logging for one named client. */
@Getter
@Setter
public class LoggingProperties {

    /** How much of the exchange is logged. Built-in default: {@code BASIC}. */
    private LogDetail level;

    /**
     * Bytes of a body written before truncation. Built-in default: 2048.
     *
     * <p>A cap rather than a warning, because the thing that overruns it is never a normal payload -
     * it is the CSV export or the base64 attachment, and one of those in the log pipeline costs more
     * than every other line the service writes that day.
     */
    @Positive
    private Integer maxBodySize;

    /**
     * Header names whose value is replaced with {@code ****}. Case-insensitive.
     *
     * <p>Built-in default: Authorization, Proxy-Authorization, Cookie, Set-Cookie, X-Api-Key,
     * X-Auth-Token, Api-Key. Declaring a list on a client replaces the default rather than adding to
     * it; {@link #getAdditionalRedactedHeaders()} is there for the common case of "the defaults plus
     * ours", so that adding one header does not silently unredact Authorization.
     */
    private List<String> redactedHeaders;

    /** Added to the redaction list rather than replacing it. */
    private List<String> additionalRedactedHeaders;

    /**
     * JSON field names redacted inside a logged body, at any depth. Case-insensitive.
     *
     * <p>Built-in default: password, secret, token, access_token, refresh_token, client_secret,
     * pin, otp, card_number, cvv.
     */
    private List<String> redactedFields;

    /** Added to {@link #getRedactedFields()} rather than replacing it. */
    private List<String> additionalRedactedFields;

    /**
     * Log the request before the call as well as after it. Built-in default: false.
     *
     * <p>Doubles the line count, and is worth it exactly once: when a call hangs, the "about to
     * call" line is the only evidence that it was ever made.
     */
    private Boolean logRequestBeforeCall;
}
