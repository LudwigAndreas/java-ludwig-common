package ru.ludwigandreas.webcore.operation;

/**
 * The headers a long-running operation speaks.
 *
 * <p>Constants rather than literals at each call site for the same reason problem codes are: a
 * header name is published, a client matches on it exactly, and a typo produces a response that is
 * well-formed and silently unusable.
 */
public final class OperationHeaders {

    /**
     * Where the eventual result will live, when that is a different URL from the status monitor.
     *
     * <p>See {@link OperationLocationHeader} for when this is the right header and when
     * {@code Location} is. The name is the one Azure's asynchronous-operation convention
     * established and the one client libraries already recognize; inventing a platform-specific
     * spelling would mean every consumer configuring theirs.
     */
    public static final String OPERATION_LOCATION = "Operation-Location";

    /**
     * How long a client should wait before polling again.
     *
     * <p>The value this platform sends is always a delta in seconds rather than an HTTP date. Both
     * are legal; a delta needs no agreement about clocks, and a client behind a proxy that rewrote
     * {@code Date} would compute a negative wait from the other form.
     */
    public static final String RETRY_AFTER = "Retry-After";

    private OperationHeaders() {
    }
}
