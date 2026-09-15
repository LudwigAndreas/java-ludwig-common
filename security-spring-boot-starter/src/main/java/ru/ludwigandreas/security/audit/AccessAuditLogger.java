package ru.ludwigandreas.security.audit;

/**
 * Where authorization decisions are recorded.
 *
 * <p>An SPI rather than a fixed implementation because the destination is a compliance decision, not a
 * technical one: some deployments need these in the application log, some in a dedicated append-only
 * store, some on a Kafka topic feeding a SIEM.
 *
 * <p>Implementations must not throw and must not block: an audit sink that is down must not take the
 * service with it. {@link Slf4jAccessAuditLogger} is the default.
 */
public interface AccessAuditLogger {

    void record(AccessDecision decision);
}
