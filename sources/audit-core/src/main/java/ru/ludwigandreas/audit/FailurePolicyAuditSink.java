package ru.ludwigandreas.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies {@link AuditFailurePolicy} in front of a real sink.
 *
 * <p>This is where "an audit sink must not be able to fail a payment" and "a change that committed
 * without its audit row must not be possible" stop contradicting each other. Both were true
 * statements about different events, and the nine SPIs this replaces resolved the contradiction by
 * each picking one and writing it into a {@code catch} block. Here the choice is a resolver, in one
 * place, per event.
 *
 * <p>Every wiring in this platform puts one of these in front of the sink the modules are given, so a
 * module never has a {@code catch} around {@code record} of its own. A module that adds one is
 * overriding a deployment's policy with a hard-coded one.
 */
public class FailurePolicyAuditSink implements AuditSink {

    private static final Logger log = LoggerFactory.getLogger(FailurePolicyAuditSink.class);

    private final AuditSink delegate;
    private final AuditFailurePolicyResolver policies;
    private final AuditSinkFailureListener listener;

    /**
     * Wraps {@code delegate}.
     *
     * @param delegate the sink that actually writes
     * @param policies what to do when it fails
     * @param listener notified of every failure whatever the policy, so a sink that has been
     *                 silently dropping observational events for a month is visible before an
     *                 auditor finds it; {@link AuditSinkFailureListener#noop()} when there is no
     *                 metrics stack
     */
    public FailurePolicyAuditSink(AuditSink delegate, AuditFailurePolicyResolver policies,
                                  AuditSinkFailureListener listener) {
        this.delegate = delegate;
        this.policies = policies == null ? AuditFailurePolicyResolver.platformDefault() : policies;
        this.listener = listener == null ? AuditSinkFailureListener.noop() : listener;
    }

    @Override
    public void record(AuditEvent event) {
        try {
            delegate.record(event);
        } catch (RuntimeException ex) {
            // CHECKSTYLE.OFF: IllegalCatch - the policy boundary. Narrowing this to the exceptions
            // one sink family throws would mean a new sink's failure mode bypassing the policy
            // silently, which is the failure this class exists to prevent.
            AuditFailurePolicy policy = policies.policyFor(event);
            listener.onFailure(event, policy, ex);
            if (policy == AuditFailurePolicy.FAIL_OPERATION) {
                // Wrapped, not rethrown as-is: the caller has to be able to tell "your change was rejected
                // because it could not be recorded" from whatever the sink's own exception type would
                // suggest, and the shared problem pipeline answers this one as a 503 - the change did not
                // happen, a retry is correct - where a leaked DataIntegrityViolationException would come
                // back as a 409 about data that was never written.
                throw new AuditWriteFailedException("Could not record the audit event for "
                        + event.category() + "/" + event.action() + " (event " + event.id() + ")", ex);
            }
            log.warn("Audit sink {} failed for {}/{} (event {}); the operation was not affected.",
                    delegate.getClass().getName(), event.category(), event.action(), event.id(), ex);
            // CHECKSTYLE.ON: IllegalCatch
        }
    }
}
