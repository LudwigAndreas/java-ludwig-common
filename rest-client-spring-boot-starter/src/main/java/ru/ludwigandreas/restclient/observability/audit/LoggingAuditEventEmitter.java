package ru.ludwigandreas.restclient.observability.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.restclient.spi.AuditEventEmitter;
import ru.ludwigandreas.restclient.spi.OutboundCallAudit;

/**
 * The default audit sink: one line per call on the {@code ludwig.restclient.audit} logger.
 *
 * <p>It is a logger and not a database because in this platform the log pipeline already is the
 * audit pipeline - the JSON encoder from observability-spring-boot-starter turns each of these into
 * a structured document with the correlation id and the service identity already attached, and the
 * retention policy on that index is the audit retention policy. A service whose compliance
 * requirements are stricter publishes its own {@code AuditEventEmitter} and names it in
 * {@code audit.emitter}.
 *
 * <p>INFO, on a dedicated logger name, so the audit stream can be routed to its own appender and its
 * own index without the application's other INFO lines coming with it.
 *
 * <p>It never blocks: an SLF4J call is what the contract on {@link AuditEventEmitter#emit} permits,
 * and a synchronous appender writing to a file is the one form of I/O cheap enough to do inline.
 */
public class LoggingAuditEventEmitter implements AuditEventEmitter {

    private static final Logger log = LoggerFactory.getLogger("ludwig.restclient.audit");

    @Override
    public void emit(OutboundCallAudit audit) {
        if (!log.isInfoEnabled()) {
            return;
        }
        // Positional placeholders rather than a formatted string: with a JSON encoder in place the
        // arguments are available as structured fields, and with a plain encoder the line still
        // reads. Building the string here would give up the first without gaining anything.
        log.info("outbound-call client={} method={} uri={} status={} outcome={} durationMs={} "
                        + "attempts={} correlationId={} traceId={} principal={} at={} failure={} headers={}",
                audit.clientName(), audit.method(), audit.uriTemplate(), audit.statusCode(),
                audit.outcome(), audit.duration().toMillis(), audit.attempts(), audit.correlationId(),
                audit.traceId(), audit.principal(), audit.at(), audit.failureType(), audit.headers());
    }
}
