package ru.ludwigandreas.audit;

/**
 * Discards every event.
 *
 * <p>For a test that is not about the trail, and for a deployment that routes auditing entirely
 * outside the application - a sidecar reading the database's own WAL, say. It exists so that
 * "auditing is off here" is a bean somebody chose rather than a null check spread through every
 * module, which is the form in which such a decision gets made by accident.
 */
public final class NoopAuditSink implements AuditSink {

    /** The single instance; the class has no state. */
    public static final NoopAuditSink INSTANCE = new NoopAuditSink();

    @Override
    public void record(AuditEvent event) {
        // Deliberately nothing.
    }
}
