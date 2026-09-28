package ru.ludwigandreas.restclient.resilience;

import java.time.Duration;

/**
 * What one attempt produced, in the terms the resilience policies reason about.
 *
 * <p>Both pipelines - blocking and reactive - reduce an attempt to this before asking any policy
 * anything. That is what makes "identical behaviour in both modes" a structural property rather
 * than a promise two code paths have to keep separately: the decision functions take an
 * {@code AttemptOutcome} and nothing else, so there is only one implementation of every decision.
 *
 * @param statusCode      the HTTP status, or {@code 0} when the attempt ended in an exception
 * @param failure         the exception, or {@code null} when a response was received
 * @param retryAfterMillis the wait the peer asked for, or {@code -1} when it asked for none
 * @param duration        wall time of this attempt
 * @param attempt         which attempt this was, counting from 1
 */
public record AttemptOutcome(
        int statusCode,
        Throwable failure,
        long retryAfterMillis,
        Duration duration,
        int attempt) {

    /** Whether a response arrived at all. */
    public boolean responded() {
        return failure == null && statusCode > 0;
    }
}
