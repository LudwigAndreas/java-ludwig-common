package ru.ludwigandreas.reconciliation.entity;

import java.util.Set;

/**
 * Lifecycle of an asynchronous remote job.
 *
 * <pre>
 * PENDING_SUBMIT -&gt; SUBMITTED -&gt; RUNNING -&gt; SUCCEEDED -&gt; COLLECTING -&gt; COLLECTED
 *        |              |           |           |             |
 *        +--------------+-----------+-----------+-------------+-&gt; FAILED | EXPIRED | CANCELLED
 *        |
 *        +-&gt; ORPHANED
 * </pre>
 */
public enum RemoteJobState {

    /**
     * The row exists and the quota slot is held, but the call has not been made - or has been made and
     * we do not know whether it was accepted.
     *
     * <p>This state is what makes the submit hazard survivable. It is written and committed
     * <em>before</em> the request leaves, so a process that dies between the POST and the response
     * leaves evidence that a job may exist rather than no evidence at all.
     */
    PENDING_SUBMIT,

    /** The partner accepted it and returned a handle. */
    SUBMITTED,

    /** The partner reports it as in progress. */
    RUNNING,

    /** The partner reports it as finished; the result has not been collected yet. */
    SUCCEEDED,

    /** Collection is in progress, resumable from {@code collect_cursor}. */
    COLLECTING,

    /** Everything was collected and staged. Terminal, and the only successful terminal state. */
    COLLECTED,

    /** The partner reports it as failed. Terminal; the demand it covered is requeued. */
    FAILED,

    /** It outlived {@code max-lifetime}, or the partner has forgotten it. Terminal; demand requeued. */
    EXPIRED,

    /** Cancelled, by expiry handling or by an operator. Terminal; demand requeued. */
    CANCELLED,

    /**
     * A submit whose outcome is unknown and cannot be resolved, under
     * {@code on-ambiguous-submit: assume-submitted}.
     *
     * <p>Terminal, and deliberately not retried: the job may well be running and billing. The quota
     * slot stays held until {@code max-lifetime}, because releasing it would let the engine start
     * another job while this one may still be occupying the partner's capacity. An orphan is a thing
     * a human should look at, which is why it has its own state and its own metric rather than being
     * folded into {@link #FAILED}.
     */
    ORPHANED;

    /** States the engine still has work to do for; anything else is settled. */
    private static final Set<RemoteJobState> NON_TERMINAL =
            Set.of(PENDING_SUBMIT, SUBMITTED, RUNNING, SUCCEEDED, COLLECTING);

    /** Whether the engine still has something to do for a job in this state. */
    public boolean isTerminal() {
        return !NON_TERMINAL.contains(this);
    }

    /** Whether a job in this state is occupying capacity on the partner's side. */
    public boolean holdsRemoteWork() {
        return this == PENDING_SUBMIT || this == SUBMITTED || this == RUNNING || this == ORPHANED;
    }
}
