package ru.ludwigandreas.webcore.operation;

import org.springframework.http.HttpHeaders;

/**
 * Which header a {@code 202} points at its status resource with.
 *
 * <h2>Both exist, and the difference is real</h2>
 *
 * <p>The question is whether the thing the client will eventually read lives at the same URL as the
 * status monitor. When it does, the status resource <em>is</em> the resource, and {@code Location}
 * is exactly what {@code Location} means. When it does not - create something slowly, then
 * {@code GET} the thing that was created somewhere else - {@code Location} would send a client to
 * the monitor and leave it with no way to learn where the result went.
 *
 * <p>There is no default. {@link OperationResponses} makes the caller name one, because a helper
 * that picked silently would pick {@code Location} for everybody and the modules that needed the
 * other would be the ones that never found out.
 */
public enum OperationLocationHeader {

    /**
     * {@code Location}: polling and reading are the same resource.
     *
     * <p>What export and notification both do. An export run is polled at
     * {@code /runs/{id}} and that same resource carries the links to its outputs; a notification
     * request is polled at {@code /notifications/{id}} and that is the request.
     */
    LOCATION(HttpHeaders.LOCATION),

    /**
     * {@code Operation-Location}: the result will live somewhere else.
     *
     * <p>For a submit that slowly creates a resource with an identity of its own. The client polls
     * the operation here and follows {@link OperationResponse#result()} to the thing that was made.
     */
    OPERATION_LOCATION(OperationHeaders.OPERATION_LOCATION);

    private final String headerName;

    OperationLocationHeader(String headerName) {
        this.headerName = headerName;
    }

    /** The HTTP header this choice writes. */
    public String headerName() {
        return headerName;
    }
}
