package ru.ludwigandreas.reconciliation.audit;

/**
 * Where audit events go.
 *
 * <p>An open SPI with a {@code @ConditionalOnMissingBean} default, so a service that already ships
 * audit to a central sink registers its own bean and this module uses it, rather than the service
 * having to tail logs or read a table this module owns.
 */
@FunctionalInterface
public interface ReconciliationAuditLogger {

    /**
     * Records an event.
     *
     * <p>Implementations must not throw. An audit sink that fails is a problem; an audit sink that
     * fails the run it was describing turns a reporting problem into an outage, and does so at
     * precisely the moment the trail would have been most useful.
     *
     * @param event what happened
     */
    void record(AuditEvent event);
}
