package ru.ludwigandreas.webcore.operation;

/**
 * The platform's vocabulary for where a long-running operation is.
 *
 * <pre>
 * PENDING -&gt; RUNNING -&gt; SUCCEEDED | FAILED | CANCELLED
 * SUCCEEDED -&gt; EXPIRED            (retention removed the result; the record stays)
 * </pre>
 *
 * <h2>Why there is one of these</h2>
 *
 * <p>Five modules of this platform had each named these states for themselves, and they disagreed
 * about the basics. Export called a finished run {@code SUCCEEDED} and file-ingest called the same
 * thing {@code COMPLETED} - one state, two words, in one platform, visible in two APIs. Every one of
 * those five was a reasonable local decision; what none of them could do was be the same as the other
 * four. This enum is the spelling the platform settled on, and the reason it is {@code SUCCEEDED}
 * rather than {@code COMPLETED} is only that export's spelling was already in a published API and
 * file-ingest's was in an actuator payload - the cheaper of the two to move.
 *
 * <h2>Domain sub-states are not replaced by this</h2>
 *
 * <p>A small common core, with richer domain lifecycles mapping <em>onto</em> it rather than being
 * flattened into it. {@code reconciliation}'s {@code COLLECTING}, {@code COLLECTED},
 * {@code PENDING_SUBMIT} and {@code ORPHANED} describe a partner system's lifecycle and carry
 * information these six constants cannot; they map to {@code RUNNING}, {@code RUNNING},
 * {@code PENDING} and {@code FAILED} while staying visible in
 * {@link OperationResponse#detail()}. A shared vocabulary that erased them would be a downgrade, and
 * somebody would correctly refuse to adopt it.
 *
 * <h2>{@code EXPIRED} is terminal and is not a failure</h2>
 *
 * <p>Worth stating because it is the one that catches people out. An operation that succeeded and
 * whose result has since been removed by a retention purge is {@code EXPIRED}: nothing went wrong,
 * and a client that treats every non-{@code SUCCEEDED} terminal state as an error will page somebody
 * for routine housekeeping. {@link #isFailure()} is what to branch on, not {@code != SUCCEEDED}.
 */
public enum OperationStatus {

    /** Accepted and waiting to start. Nothing has been attempted yet. */
    PENDING,

    /** Started and still going. The only state for which {@code Retry-After} is meaningful. */
    RUNNING,

    /** Finished, and the result is available. Terminal. */
    SUCCEEDED,

    /** Finished without a result, and will not be retried further. Terminal. */
    FAILED,

    /** Stopped because somebody asked it to stop. Terminal, and not a failure. */
    CANCELLED,

    /**
     * Succeeded once; its result has since been removed by retention. Terminal, and not a failure.
     *
     * <p>Distinct from {@link #SUCCEEDED} because the result link is gone, and distinct from
     * {@link #FAILED} because nothing went wrong. Collapsing it into either is how a retention policy
     * becomes an incident.
     */
    EXPIRED;

    /** Whether nothing further will happen to an operation in this state. */
    public boolean isTerminal() {
        return this != PENDING && this != RUNNING;
    }

    /**
     * Whether this outcome means the work did not happen and somebody should look at why.
     *
     * <p>{@link #CANCELLED} and {@link #EXPIRED} are terminal and are deliberately not failures: one
     * was asked for and the other is retention working as designed.
     */
    public boolean isFailure() {
        return this == FAILED;
    }
}
