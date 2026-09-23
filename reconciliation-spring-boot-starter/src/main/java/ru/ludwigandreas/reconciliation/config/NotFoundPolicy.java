package ru.ludwigandreas.reconciliation.config;

/**
 * What to do when the partner answers, and says it does not know a key.
 *
 * <p>This is a first-class outcome with its own policy rather than an error, because it is usually
 * permanent: a record deleted on their side, an id mistyped into this system years ago, a test row.
 * Retrying it will produce the same answer forever, and treating it as a failure is the single most
 * common cause of runaway retry loops in this class of system.
 */
public enum NotFoundPolicy {

    /**
     * Count it, and move on. The default: an id the partner has forgotten is a fact about the data,
     * not an incident, and the metric is where it belongs.
     */
    IGNORE,

    /**
     * Stage a payload-less record so the reconciler is invoked and can mark the local record as no
     * longer present upstream. The right choice when "the partner has forgotten this" is itself
     * meaningful to the domain - a deactivated account, a withdrawn catalogue entry.
     */
    MARK_MISSING,

    /**
     * Treat it as a retryable failure. Correct only for a partner whose API genuinely returns 404 for
     * "not ready yet", and a choice that should be accompanied by a short retry budget - otherwise it
     * is the runaway loop described above, on purpose.
     */
    FAIL
}
