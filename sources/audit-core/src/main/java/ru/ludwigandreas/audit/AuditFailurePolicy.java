package ru.ludwigandreas.audit;

/**
 * What happens to the caller when the audit sink fails.
 *
 * <p>The decision that gets made by accident in a {@code catch} block if it is not made here. Every
 * one of the nine SPIs this module replaces chose {@link #LOG_AND_CONTINUE} and wrote a paragraph
 * defending it, and for most of what they audited that was right. It is not right for all of it: a
 * settings change or a consent decision that committed without its audit row is precisely the thing
 * the trail exists to make impossible, and swallowing that is worse than an error the caller can
 * retry.
 */
public enum AuditFailurePolicy {

    /**
     * Log the sink's failure and let the caller carry on.
     *
     * <p>For observational events - an authorization denial, an outbound call record, a reload
     * notice. Failing a request because the audit write failed turns an audit outage into a service
     * outage, and the operational response to that is invariably to switch the trail off, which is
     * a worse outcome than the missing row.
     */
    LOG_AND_CONTINUE,

    /**
     * Rethrow, failing the operation being audited.
     *
     * <p>For state mutations. The audit write for these runs in the caller's own transaction, so
     * rethrowing rolls the change back rather than leaving a change with no record of it - which is
     * the whole point: an auditor can rely on the trail only if "not in the trail" means "did not
     * happen".
     */
    FAIL_OPERATION
}
