package ru.ludwigandreas.export.api;

/**
 * Where the export trail is written.
 *
 * <p>The module ships an SLF4J implementation, which is the right default for a service whose logs
 * are already shipped to a retained store, and the seam is here for the estate that needs the trail
 * in a table or on a topic instead. It is one method on purpose: an audit sink that could be asked
 * questions would be tempting to read from, and a trail that the thing being audited also reads is
 * a trail that will eventually be filtered by it.
 *
 * <p>Implementations must not throw. A run that failed because its audit sink was unavailable would
 * mean an outage in the trail became an outage in reporting, and the operational response to that
 * is invariably to disable the trail. The engine logs a sink's failure and continues.
 */
@FunctionalInterface
public interface ExportAuditSink {

    /**
     * Records one lifecycle transition.
     *
     * @param event what happened, already PII-redacted
     */
    void record(ExportAuditEvent event);
}
