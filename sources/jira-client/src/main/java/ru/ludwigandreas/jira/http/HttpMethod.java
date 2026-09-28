package ru.ludwigandreas.jira.http;

/**
 * HTTP methods this client issues.
 *
 * <p>{@link #isIdempotent()} is what the retry policy consults. It follows RFC 9110 rather than intuition:
 * {@code PUT} and {@code DELETE} are idempotent even though they change state, because repeating them
 * leaves the server in the same state as issuing them once. {@code POST} is not, which is why creating an
 * issue is never retried automatically - a retried create produces two issues.
 */
public enum HttpMethod {

    GET,
    POST,
    PUT,
    DELETE,
    HEAD,
    OPTIONS;

    /** Whether repeating this method is guaranteed to leave the same server state as issuing it once. */
    public boolean isIdempotent() {
        return this != POST;
    }

    /** Whether a body is meaningful for this method. */
    public boolean allowsBody() {
        return this == POST || this == PUT || this == DELETE;
    }
}
