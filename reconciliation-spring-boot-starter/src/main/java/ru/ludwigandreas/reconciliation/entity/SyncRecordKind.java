package ru.ludwigandreas.reconciliation.entity;

/**
 * What a staged row is a record <em>of</em>.
 *
 * <p>Three kinds share one table because they share a lifecycle - attempts, backoff, quarantine,
 * operator requeue - and splitting them would mean three copies of that machinery and three backlog
 * metrics that have to be read together to mean anything. What they do not share is who consumes
 * them, which is exactly what this column says: the apply pass claims records and missing markers,
 * the fetch pass reads failures.
 */
public enum SyncRecordKind {

    /** External state the partner returned. The apply pass claims these and applies them. */
    RECORD,

    /**
     * The partner answered and does not know this key, under {@code not-found: mark-missing}. The
     * apply pass claims these too and calls the reconciler's missing-record branch, so that "gone
     * upstream" is a thing the domain can act on rather than a gap in the data.
     */
    MISSING,

    /**
     * The call failed. Not something to apply - there is nothing to apply - but a backoff ticket: the
     * fetch pass skips this key until {@code next_attempt_at}, and quarantines it once the budget
     * runs out.
     *
     * <p>Without this, a key the partner reliably chokes on is refetched on every single run forever,
     * at the task's cadence, with nothing to show for it and nothing to alert on. With it, the key
     * backs off like anything else and eventually stops, visibly.
     */
    FETCH_FAILURE
}
