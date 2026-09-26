package ru.ludwigandreas.archrules.fixture.bad.audit.catalog.reporting;

/**
 * A tenth audit SPI, invented locally because this module needed somewhere for its trail to go.
 *
 * <p>This is the shape the rule exists to catch, and it is worth noting how reasonable it looks: a
 * one-method interface with a name that says what it is for, which a deployment can implement to send the
 * trail wherever it needs. Every one of the nine this platform accumulated looked exactly like this.
 */
public interface ReportAuditSink {

    /** Records one event. */
    void record(ReportAuditEvent event);
}
