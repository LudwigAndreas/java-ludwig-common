package ru.ludwigandreas.messaging.db.log;

import java.time.Duration;

/** What a handled record does to its group's position. */
public sealed interface Outcome {

    /** The record was handled or dead-lettered: the position moves past it. */
    record Advance() implements Outcome {
    }

    /**
     * Keeps the position and holds the partition for a while.
     *
     * @param failures  the record's failed deliveries so far, this one included
     * @param delay     how long until the next delivery
     * @param errorType the failure's type, never its message - a message routinely quotes the payload
     */
    record Retry(int failures, Duration delay, String errorType) implements Outcome {
    }
}
