package ru.ludwigandreas.identity.projection;

import ru.ludwigandreas.identity.feed.ApplyOutcome;

/**
 * The projection's counters. An interface so the projection works without Micrometer on the classpath.
 */
public interface IdentityMetrics {

    /** Records nothing. */
    IdentityMetrics NONE = new IdentityMetrics() {
        @Override
        public void change(String source, String type, ApplyOutcome outcome) {
        }

        @Override
        public void sweep(String source, boolean aborted, long disabled) {
        }
    };

    /**
     * Counts one change.
     *
     * @param source  the change's source
     * @param type    {@code upsert}, {@code disable} or {@code remove}
     * @param outcome what was done with it
     */
    void change(String source, String type, ApplyOutcome outcome);

    /**
     * Counts one completed sweep.
     *
     * @param source   the swept source
     * @param aborted  whether the threshold stopped it
     * @param disabled users it disabled
     */
    void sweep(String source, boolean aborted, long disabled);
}
