package ru.ludwigandreas.restclient.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;

/**
 * Overall deadline for one attempt of an {@code async} client's call.
 *
 * <p>Meaningless for {@code sync}, where the connect and read timeouts already bound the call and a
 * time limiter would need a second thread to interrupt the first. A {@code sync} client that
 * enables it is a startup error, not a silently ignored block.
 */
@Getter
@Setter
public class TimeLimiterProperties {

    /** Built-in default: false. */
    private Boolean enabled;

    /**
     * Deadline for one attempt. Built-in default: the client's {@code request-timeout}, else its
     * read timeout.
     *
     * <p>Per attempt, not per call: the retry budget ({@code retry.max-elapsed-time}) is what bounds
     * the total. Bounding both at the same value would make the second attempt impossible.
     */
    private Duration timeout;

    /** Cancel the in-flight exchange when the deadline passes. Built-in default: true. */
    private Boolean cancelRunningFuture;
}
