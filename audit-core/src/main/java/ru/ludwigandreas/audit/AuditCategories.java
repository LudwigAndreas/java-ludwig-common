package ru.ludwigandreas.audit;

/**
 * The category strings the platform's own modules use.
 *
 * <p>Constants rather than an enum, because {@link AuditEvent#category()} has to stay open: a service
 * audits its own domain through the same sink, and an enum here would mean either a release of this
 * module per consumer or a second envelope for the events it cannot name. What the constants buy is
 * that the platform's own nine subsystems spell their categories one way, which is what makes
 * "everything this person did last Tuesday" a single query.
 */
public final class AuditCategories {

    /** Report and export runs. */
    public static final String EXPORT = "export";

    /** File ingest runs. */
    public static final String INGEST = "ingest";

    /** Configuration and secret hot-reload. */
    public static final String CONFIG = "config";

    /** Transactional outbox message transitions. */
    public static final String OUTBOX = "outbox";

    /** Reconciliation runs, records, remote jobs, leases and operator actions. */
    public static final String RECONCILIATION = "reconciliation";

    /** Outbound calls to partner services. */
    public static final String OUTBOUND_CALL = "outbound-call";

    /** Authorization decisions. */
    public static final String ACCESS = "access";

    /** User settings and consent changes. */
    public static final String SETTINGS = "settings";

    /**
     * Work-dedup claims: a key replayed instead of re-executed, and a key presented with a different
     * request from the one it was claimed for.
     *
     * <p>The second of those is why this category exists rather than the events being filed under
     * whichever subsystem happened to take the claim. A client reusing one key for two requests is a
     * fact about that client, and an auditor asked to explain why a caller received a 422 needs the
     * events in one place regardless of which of a dozen endpoints the collisions landed on.
     */
    public static final String IDEMPOTENCY = "idempotency";

    /** The audit subsystem's own events, which is how a purge is itself accountable. */
    public static final String AUDIT = "audit";

    private AuditCategories() {
    }
}
