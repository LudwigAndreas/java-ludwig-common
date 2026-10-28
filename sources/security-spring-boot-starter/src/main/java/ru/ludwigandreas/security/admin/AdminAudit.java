package ru.ludwigandreas.security.admin;

import java.util.LinkedHashMap;
import java.util.Map;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.Resource;
import ru.ludwigandreas.security.principal.LudwigPrincipal;

/**
 * Records an administrative change through the platform's one audit sink.
 *
 * <p>Called by the module service that performed the change, inside the transaction that made it - not by an
 * interceptor, which can know neither the target's identity nor whether the object's state refused the change. The
 * event names the object, never its contents; the attributes passed in are assumed already redacted, as everywhere
 * else in the envelope. Whether a sink failure fails the caller is the configured failure policy's decision, which
 * is why nothing here catches what the sink throws.
 */
public class AdminAudit {

    /** The audit category of every administrative change. */
    public static final String CATEGORY = "admin";

    private final AuditSink sink;

    /**
     * Creates the helper.
     *
     * @param sink the platform's audit sink
     */
    public AdminAudit(AuditSink sink) {
        this.sink = sink;
    }

    /**
     * Records the current administrative invocation's effect on one object.
     *
     * @param resourceType the kind of object, such as {@code outbox-message}
     * @param resourceId   the object's identity
     * @param outcome      how the operation ended
     */
    public void record(String resourceType, String resourceId, AdminOutcome outcome) {
        record(resourceType, resourceId, outcome, Map.of());
    }

    /**
     * Records the current administrative invocation's effect on one object, with further already-redacted facts.
     *
     * @param resourceType the kind of object
     * @param resourceId   the object's identity, or null for an operation on a set
     * @param outcome      how the operation ended
     * @param attributes   further facts, such as a bulk operation's filter and count
     */
    public void record(String resourceType, String resourceId, AdminOutcome outcome, Map<String, Object> attributes) {
        AdminInvocation invocation = AdminInvocation.require();
        LudwigPrincipal principal = invocation.principal();
        Map<String, Object> details = new LinkedHashMap<>(attributes);
        invocation.statedReason().ifPresent(reason -> details.put("reason", reason));
        sink.record(AuditEvent.builder()
                .category(CATEGORY)
                .action(invocation.operation().id())
                .actor(new Actor(principal.subject(), principal.type().name(), principal.displayName(), null,
                        principal.tenant().orElse(null)))
                .resource(new Resource(resourceType, resourceId, null))
                .outcome(outcome.toAuditOutcome())
                .attributes(details)
                .build());
    }
}
