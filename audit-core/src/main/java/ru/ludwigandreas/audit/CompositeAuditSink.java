package ru.ludwigandreas.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * Fans one event out to several sinks.
 *
 * <p>Several at once is the normal configuration rather than an edge case: a deployment logs the
 * trail for the operators, writes it to {@code audit_event} for the auditors, and ships it through
 * the outbox to a SIEM, all from the same call.
 *
 * <p>Every delegate is attempted even when an earlier one throws, and the first failure is rethrown
 * once the rest have run. Short-circuiting would make the order of a configuration list decide
 * which sinks receive an event during a partial outage, which is the kind of dependency nobody
 * writes down and everybody is surprised by. Rethrowing at all is what lets
 * {@link FailurePolicyAuditSink} in front of this composite still fail a state mutation whose row
 * did not reach the database.
 */
public final class CompositeAuditSink implements AuditSink {

    private final List<AuditSink> delegates;

    /**
     * Composes the given sinks, in order.
     *
     * @param delegates the sinks; nulls are dropped rather than rejected, so an autoconfiguration
     *                  can pass a conditionally-created bean without a guard at every call
     */
    public CompositeAuditSink(List<AuditSink> delegates) {
        List<AuditSink> present = new ArrayList<>();
        if (delegates != null) {
            delegates.stream().filter(sink -> sink != null).forEach(present::add);
        }
        this.delegates = List.copyOf(present);
    }

    /** Composes the given sinks, in order. */
    public static AuditSink of(AuditSink... delegates) {
        return new CompositeAuditSink(delegates == null ? List.of() : List.of(delegates));
    }

    /** The sinks this composite writes to, in the order it writes to them. */
    public List<AuditSink> delegates() {
        return delegates;
    }

    @Override
    public void record(AuditEvent event) {
        RuntimeException firstFailure = null;
        for (AuditSink delegate : delegates) {
            try {
                delegate.record(event);
            } catch (RuntimeException ex) {
                // CHECKSTYLE.OFF: IllegalCatch - a fan-out boundary: one sink's failure must not
                // decide whether the others were given the event. The first one is rethrown below,
                // so nothing is swallowed.
                if (firstFailure == null) {
                    firstFailure = ex;
                } else {
                    firstFailure.addSuppressed(ex);
                }
                // CHECKSTYLE.ON: IllegalCatch
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }
}
