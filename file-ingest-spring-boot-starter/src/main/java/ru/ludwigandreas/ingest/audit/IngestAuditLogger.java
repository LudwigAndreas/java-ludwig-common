package ru.ludwigandreas.ingest.audit;

/**
 * Where the record of what this module did goes.
 *
 * <p>An SPI with a shipped SLF4J default rather than a table, because an estate that already has an
 * audit store wants these events in it and an estate that does not is well served by structured logs.
 * The module never branches on which one is installed.
 *
 * <p>Implementations are called on the run's own thread and must not throw: the engine treats an audit
 * failure as something to log and carry on from, because a run that succeeded and could not be
 * recorded is still a run that succeeded, and failing it would turn an audit outage into data not
 * arriving.
 */
public interface IngestAuditLogger {

    /**
     * Records one event.
     *
     * @param event what happened
     */
    void record(IngestAuditEvent event);
}
