package ru.ludwigandreas.identity.directory.service;

import java.time.Duration;

/** The directory feed's timer. An interface so the starter works without Micrometer. */
public interface DirectoryMetrics {

    /** Records nothing. */
    DirectoryMetrics NONE = (source, kind, outcome, elapsed) -> {
    };

    /**
     * Times one run.
     *
     * @param source  the source
     * @param kind    {@code sync} or {@code sweep}
     * @param outcome {@code succeeded}, {@code failed} or {@code aborted}
     * @param elapsed how long it took
     */
    void run(String source, String kind, String outcome, Duration elapsed);
}
