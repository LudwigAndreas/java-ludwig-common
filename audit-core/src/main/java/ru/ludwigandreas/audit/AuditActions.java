package ru.ludwigandreas.audit;

/**
 * Action names the audit subsystem emits about itself.
 *
 * <p>Only the audit module's own actions live here. Each subsystem keeps its action constants next to
 * the record that authors them - {@code IngestAuditEvent}'s nine, {@code RunStatus}'s transitions -
 * because that is where a reader looking for "what can this module say" already is, and a single
 * central list of every action in the platform is a file nobody updates.
 */
public final class AuditActions {

    /**
     * A retention purge removed events.
     *
     * <p>The purge auditing itself is not decoration. An audit trail that can be silently shortened
     * is not a trail, and this row is the difference between "nothing happened in that window" and
     * "something removed the window".
     */
    public static final String PURGED = "audit.purged";

    private AuditActions() {
    }
}
