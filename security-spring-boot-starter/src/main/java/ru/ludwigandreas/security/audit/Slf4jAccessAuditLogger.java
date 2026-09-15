package ru.ludwigandreas.security.audit;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default sink: a dedicated {@code ru.ludwigandreas.security.audit} logger, so the audit stream can be
 * routed to its own appender and retention without dragging the rest of the application log along.
 *
 * <p>Denials log at WARN and grants at DEBUG. Logging every grant at INFO sounds thorough and in
 * practice buries the denials - the events anyone actually reads - under normal traffic. Deployments
 * that must retain grants too should turn the category up, or register a logger that writes them
 * somewhere built for the volume.
 */
@RequiredArgsConstructor
public class Slf4jAccessAuditLogger implements AccessAuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("ru.ludwigandreas.security.audit");

    private final boolean logGrants;

    public Slf4jAccessAuditLogger() {
        this(false);
    }

    @Override
    public void record(AccessDecision decision) {
        if (!decision.granted()) {
            AUDIT.warn("access=denied subject={} type={} resource={} action={} id={} scope={} reason={}",
                    decision.subject(), decision.principalType(), decision.resourceType(),
                    decision.action(), decision.resourceId(), decision.scopeAccess(), decision.reason());
            return;
        }
        if (logGrants && AUDIT.isDebugEnabled()) {
            AUDIT.debug("access=granted subject={} type={} resource={} action={} id={} scope={}",
                    decision.subject(), decision.principalType(), decision.resourceType(),
                    decision.action(), decision.resourceId(), decision.scopeAccess());
        }
    }
}
