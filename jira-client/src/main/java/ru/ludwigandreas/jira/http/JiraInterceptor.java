package ru.ludwigandreas.jira.http;

/**
 * A hook around every request the client sends, in the shape that lets it both observe and replace.
 *
 * <p>Interceptors are the extension point for the things an enterprise deployment needs and a Jira client
 * has no business knowing about: propagating a trace context or a correlation id, stamping a tenant header,
 * enforcing an outbound circuit breaker, recording a request/response pair for a support ticket.
 *
 * <p>They run inside the retry loop, once per attempt, so an interceptor that stamps a per-attempt header
 * (a retry counter, a fresh idempotency key) sees each attempt. An interceptor that must run exactly once
 * per logical call belongs in the caller, not here.
 *
 * <p>An interceptor that does not call {@link Chain#proceed} short-circuits the request - which is how a
 * cache or a test double is installed - and one that calls it twice re-sends. Both are legitimate; neither
 * happens by accident.
 */
@FunctionalInterface
public interface JiraInterceptor {

    /**
     * Processes one attempt.
     *
     * @param request the request as the previous interceptor left it
     * @param chain the rest of the pipeline, ending in the transport
     * @return the response to hand back up the chain
     */
    JiraResponse intercept(JiraRequest request, Chain chain);

    /** The remainder of the interceptor pipeline. */
    @FunctionalInterface
    interface Chain {

        /**
         * Hands the request to the next interceptor, or to the transport when this is the last one.
         *
         * @param request the request to pass on, possibly modified
         * @return the response from further down the chain
         */
        JiraResponse proceed(JiraRequest request);
    }
}
