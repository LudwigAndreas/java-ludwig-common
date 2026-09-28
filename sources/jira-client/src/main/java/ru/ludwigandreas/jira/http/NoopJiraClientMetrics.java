package ru.ludwigandreas.jira.http;

import java.time.Duration;

/**
 * The metrics implementation used when no registry was configured: every method returns immediately.
 *
 * <p>A null-object rather than a {@code null} check at each call site, so the instrumented and
 * uninstrumented paths through the client are the same code and cannot drift.
 */
final class NoopJiraClientMetrics implements JiraClientMetrics {

    static final JiraClientMetrics INSTANCE = new NoopJiraClientMetrics();

    private NoopJiraClientMetrics() {
    }

    @Override
    public void recordCall(String operation, HttpMethod method, int status, Duration duration) {
        // Intentionally empty.
    }

    @Override
    public void recordRetry(String operation, String reason) {
        // Intentionally empty.
    }

    @Override
    public void recordRateLimited(String operation) {
        // Intentionally empty.
    }
}
