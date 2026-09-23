package ru.ludwigandreas.restclient.spi;

/**
 * Where audit records go.
 *
 * <p>The default writes one structured line to the {@code ludwig.restclient.audit} logger, which is
 * enough for a deployment whose log pipeline is already the audit pipeline. A service that has to
 * put them in a table, on a topic, or in a vendor's API publishes a bean of this type and names it
 * in {@code audit.emitter}.
 *
 * <p><strong>{@link #emit} must not block.</strong> It is called from the calling thread of a
 * {@code sync} call and from a Reactor thread of an {@code async} one; an emitter that writes to a
 * database synchronously adds its own latency and its own failure mode to every business call the
 * service makes. Hand the record to a queue or an executor and return. An emitter that throws is
 * caught and counted like a listener - an audit sink must not be able to fail a payment - so an
 * emitter that drops records silently is a bug the service has to find for itself.
 */
@FunctionalInterface
public interface AuditEventEmitter {

    /** Records one completed outbound call. */
    void emit(OutboundCallAudit audit);
}
