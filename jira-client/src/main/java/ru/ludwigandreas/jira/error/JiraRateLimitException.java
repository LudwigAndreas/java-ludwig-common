package ru.ludwigandreas.jira.error;

import java.time.Duration;
import java.util.Optional;

/**
 * Jira answered 429. Data Center raises this from its rate-limiting feature, and a reverse proxy in front
 * of Jira may raise it too.
 *
 * <p>{@link #getRetryAfter()} carries the server's {@code Retry-After} when it sent one. The client's own
 * retry policy already honours it; this exception only reaches the caller once the retry budget is spent,
 * and the duration is exposed so a caller can decide to stop scheduling work for that long.
 */
public class JiraRateLimitException extends JiraApiException {

    private static final long serialVersionUID = 1L;

    private final Duration retryAfter;

    public JiraRateLimitException(int status, String method, String uri, ErrorCollection errors, String body,
                                  Duration retryAfter) {
        super(status, method, uri, errors, body);
        this.retryAfter = retryAfter;
    }

    /** How long the server asked the caller to wait, when it said. */
    public Optional<Duration> getRetryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
