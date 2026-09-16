package ru.ludwigandreas.webcore.trace;

import java.util.Optional;

/**
 * Supplies the correlation id that is published on every problem document.
 *
 * <p>This exists because "quote the trace id from the response" is the only useful thing an error
 * message can tell a user about a 500: the detail must not describe the fault, but support still has
 * to be able to find the one request that failed. Publishing the id in the body - not only in a
 * header a browser console hides - is what makes that instruction actionable.
 */
public interface TraceIdProvider {

    /** The current request's trace id, or empty when nothing is tracing. */
    Optional<String> currentTraceId();

    /** Used when tracing is switched off, so the renderer needs no null checks. */
    static TraceIdProvider none() {
        return Optional::empty;
    }
}
