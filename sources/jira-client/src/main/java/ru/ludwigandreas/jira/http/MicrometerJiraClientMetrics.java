package ru.ludwigandreas.jira.http;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;

/**
 * Publishes the client's call, retry and rate-limit signals to a Micrometer registry.
 *
 * <p>The only class in this module that references Micrometer, which is what allows the dependency to be
 * {@code optional}: a consumer that never calls
 * {@link ru.ludwigandreas.jira.JiraClientBuilder#meterRegistry(MeterRegistry)} never loads this class and
 * therefore never needs Micrometer on the classpath.
 *
 * <p>Meters published:
 *
 * <ul>
 *   <li>{@code jira.client.requests} - timer, tagged {@code operation}, {@code method}, {@code status},
 *       {@code outcome}. {@code status} is bucketed to {@code 2xx}/{@code 4xx}/{@code 5xx}/{@code none}
 *       rather than carrying the exact code, keeping the tag cardinality at four.</li>
 *   <li>{@code jira.client.retries} - counter, tagged {@code operation} and {@code reason}.</li>
 *   <li>{@code jira.client.rate.limited} - counter, tagged {@code operation}.</li>
 * </ul>
 */
public final class MicrometerJiraClientMetrics implements JiraClientMetrics {

    private static final String REQUESTS = "jira.client.requests";
    private static final String RETRIES = "jira.client.retries";
    private static final String RATE_LIMITED = "jira.client.rate.limited";
    private static final int STATUS_CLASS_DIVISOR = 100;
    private static final int LOWEST_ERROR_STATUS = 400;

    private final MeterRegistry registry;

    public MicrometerJiraClientMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordCall(String operation, HttpMethod method, int status, Duration duration) {
        registry.timer(REQUESTS, Tags.of(
                        "operation", operation,
                        "method", method.name(),
                        "status", bucket(status),
                        "outcome", outcome(status)))
                .record(duration);
    }

    @Override
    public void recordRetry(String operation, String reason) {
        registry.counter(RETRIES, Tags.of("operation", operation, "reason", reason)).increment();
    }

    @Override
    public void recordRateLimited(String operation) {
        registry.counter(RATE_LIMITED, Tags.of("operation", operation)).increment();
    }

    private static String bucket(int status) {
        if (status < 0) {
            return "none";
        }
        return (status / STATUS_CLASS_DIVISOR) + "xx";
    }

    private static String outcome(int status) {
        if (status < 0) {
            return "FAILURE";
        }
        return status < LOWEST_ERROR_STATUS ? "SUCCESS" : "ERROR";
    }
}
