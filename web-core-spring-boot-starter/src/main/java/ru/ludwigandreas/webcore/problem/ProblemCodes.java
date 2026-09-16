package ru.ludwigandreas.webcore.problem;

import java.util.Locale;

/**
 * The problem codes this starter itself emits, and therefore the ones every service built on it
 * answers with for a framework-level failure.
 *
 * <p>They are constants rather than string literals scattered through the advice because a code is
 * published in the response body: a client is expected to branch on {@code code} instead of parsing
 * a translated {@code detail}, which makes each one part of the API contract. The keys in
 * {@code i18n/ludwig-web-messages.properties} are exactly these values, plus a {@code .title}
 * sibling for each.
 *
 * <p>A service's <em>own</em> business codes do not belong here - they are named by its own
 * {@link LocalizedException} subclasses, in its own namespace.
 */
public final class ProblemCodes {

    /** Namespace prefix for every code below, so a service can spot a framework error at a glance. */
    public static final String PREFIX = "ludwig.web.error.";

    /** Bean Validation rejected the request body; carries the {@code violations} member. */
    public static final String VALIDATION = PREFIX + "validation";
    /** A parameter, path variable or header was absent, or could not be converted. */
    public static final String BAD_REQUEST = PREFIX + "bad-request";
    /** The body could not be parsed at all - malformed JSON, truncated payload. */
    public static final String MALFORMED_REQUEST = PREFIX + "malformed-request";
    /** No credential, or an unusable one, reached a controller rather than the filter chain. */
    public static final String UNAUTHORIZED = PREFIX + "unauthorized";
    /** Authenticated, but not entitled. */
    public static final String FORBIDDEN = PREFIX + "forbidden";
    /** The addressed resource does not exist. */
    public static final String NOT_FOUND = PREFIX + "not-found";
    /** The HTTP method is not supported by this resource; carries the {@code Allow} header. */
    public static final String METHOD_NOT_ALLOWED = PREFIX + "method-not-allowed";
    /** Nothing acceptable to the caller's {@code Accept} header can be produced. */
    public static final String NOT_ACCEPTABLE = PREFIX + "not-acceptable";
    /** The request's {@code Content-Type} is not supported. */
    public static final String UNSUPPORTED_MEDIA_TYPE = PREFIX + "unsupported-media-type";
    /** The request conflicts with the current state of the resource. */
    public static final String CONFLICT = PREFIX + "conflict";
    /** Two writers raced and this one lost - the same answer a stale-version request gets. */
    public static final String CONCURRENT_MODIFICATION = PREFIX + "concurrent-modification";
    /** The payload exceeds the configured maximum size. */
    public static final String PAYLOAD_TOO_LARGE = PREFIX + "payload-too-large";
    /** A rate limit or quota was exceeded. */
    public static final String TOO_MANY_REQUESTS = PREFIX + "too-many-requests";
    /** Anything unhandled. The only code whose detail deliberately says nothing specific. */
    public static final String INTERNAL = PREFIX + "internal";
    /** An upstream dependency failed or timed out. */
    public static final String UPSTREAM_UNAVAILABLE = PREFIX + "upstream-unavailable";

    /**
     * The generic code for an outcome nothing more specific was determined for.
     *
     * <p>Derived from the enum constant's name rather than from a switch, so declaring a new
     * {@link ProblemStatus} needs no edit here and cannot leave a gap - only a bundle key. The
     * constants above are the cases where the <em>cause</em> is known more precisely than the status:
     * a 400 from malformed JSON and a 400 from a failed constraint deserve different text, and both
     * are {@link ProblemStatus#INVALID}.
     */
    public static String forStatus(ProblemStatus status) {
        return PREFIX + status.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private ProblemCodes() {
    }
}
