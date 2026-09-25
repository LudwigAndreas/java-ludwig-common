package ru.ludwigandreas.ingest.audit;

import lombok.extern.slf4j.Slf4j;

/**
 * The shipped audit sink: one structured log line per event.
 *
 * <p>At INFO, deliberately. These are the events somebody reconstructs a morning from, and an audit
 * trail at DEBUG is an audit trail that is switched off in every environment where it would have been
 * needed.
 */
@Slf4j
public class Slf4jIngestAuditLogger implements IngestAuditLogger {

    @Override
    public void record(IngestAuditEvent event) {
        log.info("ingest audit task={} run={} action={} details={}",
                event.task(), event.runId(), event.action(), event.details());
    }
}
