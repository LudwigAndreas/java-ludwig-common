package ru.ludwigandreas.identity.feed;

import java.util.Collection;

/**
 * A complete listing of one source in progress. Subjects are staged as they are listed; {@link #complete()} disables,
 * in one transaction, every active user of the source that was not listed - unless that would disable more than
 * {@code ludwig.identity.sweep.max-disable-ratio} of them, in which case nothing changes. A sweep that is neither
 * completed nor abandoned is cleaned up by a later sweep.
 */
public interface IdentitySweep {

    /**
     * Records subjects the listing contained. Upserts for them are applied separately, through the projection.
     *
     * @param subjects listed subjects
     */
    void listed(Collection<String> subjects);

    /**
     * Ends the listing and disables the users it did not contain, or aborts past the threshold.
     *
     * @return what the sweep did
     */
    SweepResult complete();

    /** Ends the listing without changing any user. */
    void abandon();

    /**
     * The outcome of a completed sweep.
     *
     * @param source   the swept source
     * @param aborted  whether the threshold stopped it; then nothing changed
     * @param active   the source's active users before the sweep
     * @param unlisted active users the listing did not contain
     * @param disabled users disabled; 0 when aborted
     */
    record SweepResult(String source, boolean aborted, long active, long unlisted, long disabled) {
    }
}
