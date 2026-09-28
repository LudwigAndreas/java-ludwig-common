package ru.ludwigandreas.jira.http;

import java.time.Duration;

/**
 * Instrumentation seam for every call the client makes.
 *
 * <p>An interface rather than a direct Micrometer dependency so that the client works with no metrics
 * backend at all - the default is {@link #noop()} - and so that a team on something other than Micrometer
 * can bind their own. {@link MicrometerJiraClientMetrics} is the implementation for Micrometer and is the
 * only class in this module that touches it, which is what lets the dependency stay {@code optional}.
 *
 * <p>Every method takes the request's {@link JiraRequest#operation() operation} rather than its URI,
 * because the operation is bounded and the URI is not: one time series per issue key is a metrics outage.
 */
public interface JiraClientMetrics {

    /** An implementation that records nothing, used when no registry was supplied. */
    static JiraClientMetrics noop() {
        return NoopJiraClientMetrics.INSTANCE;
    }

    /**
     * A call finished, successfully or not.
     *
     * @param operation low-cardinality operation name
     * @param method HTTP method
     * @param status HTTP status, or -1 when no response was received
     * @param duration wall time of the whole call, including retries
     */
    void recordCall(String operation, HttpMethod method, int status, Duration duration);

    /**
     * A request was re-sent.
     *
     * @param operation low-cardinality operation name
     * @param reason why it was retried: a status code as text, or the transport failure's simple class name
     */
    void recordRetry(String operation, String reason);

    /**
     * A rate limit was hit, whether or not the retry eventually succeeded. Separated from
     * {@link #recordRetry} because it is the one signal that means "reduce concurrency", and it deserves
     * its own alert.
     *
     * @param operation low-cardinality operation name
     */
    void recordRateLimited(String operation);
}
