package ru.ludwigandreas.fileaction.api;

/**
 * What happens to the other rows when one of them cannot be applied.
 *
 * <h2>There is no default, and that is the point</h2>
 *
 * <p>Modelled on {@code cache-spring-boot-starter}'s {@code CachePurpose}, which {@code CLAUDE.md} singles
 * out as "the only mistake the module cannot detect for you". Every candidate default here is silently wrong
 * for somebody:
 *
 * <ul>
 *   <li>Default to {@link #PER_ROW} and an accounting import that must balance applies two thirds of a
 *       journal entry.</li>
 *   <li>Default to {@link #ALL_OR_NOTHING} and a four-hundred-row contact list is refused in full over one
 *       mistyped email, which users experience as the feature not working.</li>
 * </ul>
 *
 * <p>Neither failure is visible in a test, because a test fixture has either no bad rows or all bad rows. Both
 * are visible in production, as applied business data. So an action that does not declare a policy does not
 * start, and the startup message names the three values.
 *
 * <p>{@link #ALL_OR_NOTHING} is the only policy a {@code DocumentHandler} may have, and that is not a
 * configuration choice either - it is what "all these rows are one business fact" means. Configuring another
 * for one is refused at startup rather than quietly ignored.
 */
public enum CommitPolicy {

    /**
     * One refused row means nothing is applied.
     *
     * <p>Right when a partial result is a wrong result: a journal entry, a trial balance, a stock take. The
     * cost is honest - a four-hundred-row file is refused over one typo - and for these domains that is the
     * cheaper outcome.
     */
    ALL_OR_NOTHING,

    /**
     * One refused row rolls back its batch; earlier batches stand.
     *
     * <p>Right when rows are independent but restarting from a known point is cheap - a bulk price update,
     * where the user re-submits the tail of the file. The submission reports which batch failed, because
     * without that the user cannot tell which rows to re-send.
     */
    PER_BATCH,

    /**
     * One refused row is one reject; every other row applies.
     *
     * <p>Right when rows are genuinely independent: an order import, a contact list, a catalogue update. The
     * submission still reaches {@code APPLIED} with a reject count, because from the caller's point of view
     * the action happened.
     */
    PER_ROW;

    /** Whether a single reject prevents everything. */
    public boolean isAllOrNothing() {
        return this == ALL_OR_NOTHING;
    }
}
