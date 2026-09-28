package ru.ludwigandreas.audit;

/**
 * Notified whenever a sink fails, whatever {@link AuditFailurePolicy} then decides.
 *
 * <p>Separate from the policy because the two answer different questions. The policy decides whether
 * this caller survives; the listener is how anybody finds out at all. Without it, a
 * {@link AuditFailurePolicy#LOG_AND_CONTINUE} sink that has been failing since a schema change is a
 * warning line in a log nobody reads, and the first person to notice is an auditor asking why a month
 * is missing.
 *
 * <p>In {@code audit-spring-boot-starter} this is bound to a Micrometer counter. It lives here, in
 * the module with no dependencies, so that a deployment without a metrics stack still has the seam.
 */
@FunctionalInterface
public interface AuditSinkFailureListener {

    /**
     * One failed write.
     *
     * @param event  the event that was not recorded
     * @param policy what is about to be done about it
     * @param cause  why the sink failed
     */
    void onFailure(AuditEvent event, AuditFailurePolicy policy, RuntimeException cause);

    /** For a deployment with no metrics stack at all. */
    static AuditSinkFailureListener noop() {
        return (event, policy, cause) -> {
            // Deliberately nothing; FailurePolicyAuditSink still logs.
        };
    }
}
