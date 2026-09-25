package ru.ludwigandreas.export.audit;

import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.export.api.ExportAuditEvent;
import ru.ludwigandreas.export.api.ExportAuditSink;

/**
 * Writes the export trail to a dedicated logger.
 *
 * <p>A logger of its own - {@code ru.ludwigandreas.export.audit} - rather than this class's, so that
 * an operator can route the trail to a retained store and a different retention from the service's
 * ordinary logs without filtering on message content. That is the whole reason a default
 * implementation of the sink is worth shipping: an estate with no audit table still has somewhere
 * for the trail to go, and it goes there in one grep-able place.
 *
 * <p>One line per event, with the fields in a fixed order and nothing that varies in shape, because
 * the consumer is a log pipeline rather than a person. Parameters are already PII-redacted by the
 * time an event is built, so this class has no redaction of its own to forget.
 *
 * <p>Never throws: the contract on {@link ExportAuditSink} says so, and it says so because an outage
 * in the trail must not become an outage in reporting - the operational response to that is
 * invariably to switch the trail off.
 */
@Slf4j(topic = "ru.ludwigandreas.export.audit")
public class Slf4jExportAuditSink implements ExportAuditSink {

    @Override
    public void record(ExportAuditEvent event) {
        try {
            log.info("export run={} status={} definition={} savedReport={} requester={} rows={}"
                            + " formats={} columns={} degraded={} outputs={} correlation={} at={}",
                    event.runId(), event.status(), event.definitionKey(), event.savedReportId(),
                    event.principalId(), event.rowsWritten(), event.formats(), event.columnIds(),
                    event.degradedStages(), event.outputUris(), event.correlationId(), event.at());
        } catch (RuntimeException e) {
            // A logging framework that throws is already a problem; making it this module's problem
            // as well would mean a misconfigured appender stopped reports being produced.
            log.warn("Could not record the export audit event for run {}", event.runId(), e);
        }
    }
}
