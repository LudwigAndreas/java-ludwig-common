package ru.ludwigandreas.audit;

/**
 * The audit event could not be recorded, and the operation it describes must not stand.
 *
 * <p>Thrown by {@link FailurePolicyAuditSink} where {@link AuditFailurePolicy#FAIL_OPERATION} applies, which
 * is the state-mutation categories. A distinct type rather than the sink's own exception - a
 * {@code DataIntegrityViolationException}, a broker timeout - because the caller has to be able to tell "your
 * change was rejected" from "your change succeeded and something else broke", and because the two are
 * answered differently: {@code audit-spring-boot-starter}'s {@code AuditProblemMapper} renders this as a
 * <strong>503</strong>, which tells a client the change did not happen and a retry is correct, where a 500
 * tells it nothing.
 *
 * <p>In {@code audit-core} rather than in the starter, because the class that throws it is here. It was
 * declared in the starter first, next to its problem mapper, and was consequently never thrown by anything -
 * the policy decorator rethrew the sink's own exception and the mapper had no addressee. A mapped exception
 * nothing raises is a documented behaviour that does not exist.
 */
public class AuditWriteFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message which event could not be recorded
     * @param cause   the sink's own failure
     */
    public AuditWriteFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
