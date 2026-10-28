package ru.ludwigandreas.fileaction.api;

import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * Where one submission is in <em>this module's</em> lifecycle.
 *
 * <pre>
 *                        UPLOADED
 *                           |
 *          +----------------+----------------+
 *          |                |                |
 *      REJECTED         VALIDATED         APPLYING          (mode DIRECT skips VALIDATED)
 *                           |                |
 *                      (confirm)              |
 *                           +----------------+
 *                           |                |
 *                        EXPIRED           APPLIED
 *                     (TTL elapsed)
 *
 *   CANCELLED - from UPLOADED or APPLYING at the next checkpoint after a cancel, from VALIDATED at once
 * </pre>
 *
 * <h2>Why this exists next to {@link OperationStatus} rather than instead of it</h2>
 *
 * <p>The platform has exactly one status vocabulary and no module may restate it - export's
 * {@code RunStatus} and file-ingest's {@code IngestRunStatus} were restatements, and reconciling them
 * cost a Liquibase changeset because {@code COMPLETED} had been persisted. This enum is deliberately
 * <em>not</em> that. Its non-terminal and success values carry information the core vocabulary cannot
 * express; {@link #EXPIRED} and {@link #CANCELLED} share a name with the core state they map to, because a
 * stop and an expiry are exactly what the core states say and nothing more.
 *
 * <ul>
 *   <li>{@link #UPLOADED} and {@link #VALIDATED} both map to {@link OperationStatus#PENDING}, and the
 *       difference between them is the whole of the confirm feature - one is waiting for the module,
 *       the other is waiting for a human. A client showing a spinner for the first and a "confirm"
 *       button for the second cannot tell them apart from the core status alone.</li>
 *   <li>{@link #REJECTED} maps to {@link OperationStatus#FAILED}, and means the file was read and
 *       refused - as distinct from the apply having thrown, which is also {@code FAILED} and is a
 *       different conversation with support.</li>
 * </ul>
 *
 * <p>This is the same shape as {@code reconciliation}'s {@code RemoteJobState}, which the
 * {@code long-running-operations} capability names as the permitted case: a richer domain lifecycle
 * that maps onto the core and stays visible in {@code OperationResponse.detail()}.
 * {@code architecture-rules}' {@code RuleGroup.OPERATIONS} fails the build for an enum made only of core
 * vocabulary words; this one is not, because {@code UPLOADED}, {@code VALIDATED}, {@code APPLYING} and
 * {@code APPLIED} are domain states.
 */
public enum FileActionState {

    /** Admitted, stored, and not yet read. Maps to {@code PENDING}. */
    UPLOADED(OperationStatus.PENDING),

    /**
     * Read, bound and validated; waiting for a human to confirm.
     *
     * <p>Maps to {@code PENDING} rather than {@code RUNNING} because nothing is executing: the module
     * is not working on it and will not until somebody calls confirm. A {@code RUNNING} here would
     * make every client's progress indicator lie.
     */
    VALIDATED(OperationStatus.PENDING),

    /** The handler is being run over the bound rows. Maps to {@code RUNNING}. */
    APPLYING(OperationStatus.RUNNING),

    /** The handler finished. Maps to {@code SUCCEEDED}, including when some rows were rejected. */
    APPLIED(OperationStatus.SUCCEEDED),

    /**
     * The file was read and refused, or too many of its rows were.
     *
     * <p>Maps to {@code FAILED}. Nothing was applied - a submission that applied some rows and
     * rejected others is {@link #APPLIED} with a reject count, because from the caller's point of
     * view the action happened.
     */
    REJECTED(OperationStatus.FAILED),

    /**
     * Retention removed the stored upload and artifacts, or a {@code VALIDATED} submission was never
     * confirmed within its TTL.
     *
     * <p>Maps to {@link OperationStatus#EXPIRED}, which is terminal and <em>not</em> a failure. Both
     * causes are housekeeping working as designed, and a client that pages somebody for either has
     * branched on {@code != SUCCEEDED} instead of on {@code isFailure()}.
     */
    EXPIRED(OperationStatus.EXPIRED),

    /**
     * A cancel was requested and the submission stopped before it finished.
     *
     * <p>Maps to {@link OperationStatus#CANCELLED}. Rows applied before the stop are kept - a stop happens
     * between transactions, never inside one - and {@code rowsApplied} reports them. Not a failure: the
     * caller asked for it.
     */
    CANCELLED(OperationStatus.CANCELLED);

    private final OperationStatus status;

    FileActionState(OperationStatus status) {
        this.status = status;
    }

    /**
     * The platform-wide status this domain state maps onto.
     *
     * @return the core status; never null
     */
    public OperationStatus status() {
        return status;
    }

    /** Whether nothing further will happen to a submission in this state without a new request. */
    public boolean isTerminal() {
        return status.isTerminal();
    }

    /** Whether a submission in this state is waiting for a person rather than for the module. */
    public boolean awaitsHuman() {
        return this == VALIDATED;
    }
}
