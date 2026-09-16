package ru.ludwigandreas.webcore.problem;

/**
 * Transport-neutral outcome of a failed operation.
 *
 * <p>Every {@link LocalizedException} declares one. Keeping it an enum rather than an
 * {@code HttpStatus} is what lets a service layer state "this is a conflict" without depending on
 * Spring's web stack - the same exception is then equally meaningful to a message consumer or a
 * scheduled job that has no HTTP response to write.
 *
 * <p>The numeric code is carried as a plain {@code int} rather than as an {@code HttpStatus} for the
 * same reason. It is deliberately <em>not</em> hidden: mapping the enum in an exhaustive switch in
 * the web layer was the earlier design, and it meant every new outcome had to be added in two
 * places, with the compiler pointing at the switch only after someone had already shipped the enum
 * constant. Here a new constant is usable the moment it is declared.
 */
public enum ProblemStatus {

    /** The request itself is malformed or violates a constraint. */
    INVALID(400),
    /** No usable credential was presented. */
    UNAUTHORIZED(401),
    /** The caller is authenticated but not entitled to what it asked for. */
    FORBIDDEN(403),
    /** The addressed resource does not exist. */
    NOT_FOUND(404),
    /** The resource exists but does not support this method. */
    METHOD_NOT_ALLOWED(405),
    /** No representation acceptable to the caller can be produced. */
    NOT_ACCEPTABLE(406),
    /** The request conflicts with the current state of the resource. */
    CONFLICT(409),
    /** The resource existed and is permanently gone. */
    GONE(410),
    /** A conditional request's precondition did not hold. */
    PRECONDITION_FAILED(412),
    /** The payload is larger than this endpoint accepts. */
    PAYLOAD_TOO_LARGE(413),
    /** The payload's media type is not supported. */
    UNSUPPORTED_MEDIA_TYPE(415),
    /**
     * The request was well-formed and addressed a valid resource, but its content cannot be acted
     * on - a referenced entity that does not exist, a state transition the domain forbids.
     */
    UNPROCESSABLE(422),
    /** The resource is locked by another operation. */
    LOCKED(423),
    /** The caller exceeded a rate limit or quota. */
    TOO_MANY_REQUESTS(429),
    /** An unexpected fault on this side. */
    INTERNAL(500),
    /** A path that is declared but not implemented yet. */
    NOT_IMPLEMENTED(501),
    /** An upstream dependency answered unusably. */
    BAD_GATEWAY(502),
    /** This service is up but cannot serve the request right now. */
    SERVICE_UNAVAILABLE(503),
    /** An upstream dependency did not answer in time. */
    GATEWAY_TIMEOUT(504);

    private final int code;

    ProblemStatus(int code) {
        this.code = code;
    }

    /** The HTTP status code this outcome is rendered as. */
    public int code() {
        return code;
    }

    /**
     * The outcome that renders as {@code code}.
     *
     * <p>Falls back rather than throwing: an unmapped status still has to produce a problem, and
     * "some 4xx we have no constant for" is a client error whatever its exact number.
     */
    public static ProblemStatus ofCode(int code) {
        for (ProblemStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return code >= 500 || code < 400 ? INTERNAL : INVALID;
    }

    /**
     * Whether this outcome means "we failed", as opposed to "your request was refused".
     *
     * <p>Used to decide log level and whether the cause's stack trace is worth recording: a 404 is a
     * normal answer and logging a stack trace for it only buries the 500s.
     */
    public boolean isServerError() {
        return code >= 500;
    }
}
