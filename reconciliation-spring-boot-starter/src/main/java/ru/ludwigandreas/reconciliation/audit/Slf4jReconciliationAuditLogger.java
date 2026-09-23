package ru.ludwigandreas.reconciliation.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes audit events to a dedicated logger, {@code ru.ludwigandreas.reconciliation.audit}, so a
 * deployment can route the audit trail somewhere different from the module's operational logging
 * without changing any code.
 *
 * <p>The fields are logged individually rather than concatenated into a sentence, because the
 * observability starter's JSON encoder turns them into queryable fields, and an audit trail nobody
 * can query is a file.
 */
public class Slf4jReconciliationAuditLogger implements ReconciliationAuditLogger {

    private static final Logger log = LoggerFactory.getLogger("ru.ludwigandreas.reconciliation.audit");

    @Override
    public void record(AuditEvent event) {
        log.info("reconciliation audit task={} category={} event={} subject={} {} -> {} run={} principal={} : {}",
                event.taskName(), event.category(), event.event(), event.subject(),
                event.fromState(), event.toState(), event.runId(), event.principal(), event.detail());
    }
}
