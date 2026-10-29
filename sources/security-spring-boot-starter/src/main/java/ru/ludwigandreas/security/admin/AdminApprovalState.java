package ru.ludwigandreas.security.admin;

import org.springframework.http.HttpStatus;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * Where a four-eyes approval stands.
 *
 * <p>{@code PENDING} moves to {@code APPROVING}, {@code REJECTED}, {@code WITHDRAWN} or {@code EXPIRED};
 * {@code APPROVING} moves to {@code EXECUTED}, or to {@code INTERRUPTED} when the replica died while executing. An
 * interrupted approval is never run again, because the handler may have committed.
 */
public enum AdminApprovalState {

    /** Recorded, waiting for a second holder of the operation. */
    PENDING,

    /** Approved; the stored request is being executed. */
    APPROVING,

    /** Executed; the outcome records how the handler answered. */
    EXECUTED,

    /** Refused by a second holder. */
    REJECTED,

    /** Withdrawn by its requester. */
    WITHDRAWN,

    /** Not decided within the configured time. */
    EXPIRED,

    /** The execution was cut off - the replica stopped while approving - and is not retried. */
    INTERRUPTED;

    /**
     * Whether nothing can happen to an approval in this state any more.
     *
     * @return true for every state but {@code PENDING} and {@code APPROVING}
     */
    public boolean isTerminal() {
        return this != PENDING && this != APPROVING;
    }

    /**
     * The operation envelope status an approval in this state shows.
     *
     * @param outcomeStatus the executed request's HTTP status, for {@code EXECUTED}
     * @return the envelope status
     */
    public OperationStatus envelopeStatus(Integer outcomeStatus) {
        return switch (this) {
            case PENDING -> OperationStatus.PENDING;
            case APPROVING -> OperationStatus.RUNNING;
            case EXECUTED -> outcomeStatus != null && HttpStatus.valueOf(outcomeStatus).is2xxSuccessful()
                    ? OperationStatus.SUCCEEDED : OperationStatus.FAILED;
            case REJECTED, WITHDRAWN -> OperationStatus.CANCELLED;
            case EXPIRED -> OperationStatus.EXPIRED;
            case INTERRUPTED -> OperationStatus.FAILED;
        };
    }
}
