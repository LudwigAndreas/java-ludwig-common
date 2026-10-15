package ru.ludwigandreas.grpc.client.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.Resource;

/**
 * One outbound gRPC call, as this module's typed audit event.
 *
 * <p>Not a second audit mechanism: it is rendered by {@link #toAuditEvent()} into the platform's
 * one {@code AuditSink}, under {@code category=outbound-call} beside {@code rest-client}'s calls,
 * and it carries no payload and no metadata value.
 *
 * @param clientName    the client
 * @param method        the full method name, {@code package.Service/Method}
 * @param code          the gRPC code the call ended with
 * @param outcome       {@code SUCCESS}, {@code FAILURE} or {@code NOT_PERMITTED}
 * @param duration      how long the call took
 * @param attempts      attempts sent, retries included; 0 for a refused call
 * @param correlationId the correlation id, or {@code null}
 * @param principal     the caller on whose behalf the call was made, or {@code null} for the service
 * @param at            when the call started
 * @param failureType   the failure's type, or {@code null}
 */
public record GrpcCallAudit(String clientName, String method, String code, String outcome, Duration duration,
                            int attempts, String correlationId, String principal, Instant at, String failureType) {

    /**
     * The audit action.
     */
    public static final String ACTION = "partner.called";

    /**
     * The call succeeded.
     */
    public static final String SUCCESS = "SUCCESS";

    /**
     * This service's own policy refused the call.
     */
    public static final String NOT_PERMITTED = "NOT_PERMITTED";

    /**
     * The call failed.
     */
    public static final String FAILURE = "FAILURE";

    private static final String SERVICE = "SERVICE";

    /**
     * The event the platform's sink records.
     *
     * @return the audit event
     */
    public AuditEvent toAuditEvent() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("clientName", clientName);
        attributes.put("transport", "grpc");
        attributes.put("code", code);
        attributes.put("outcome", outcome);
        attributes.put("durationMs", duration.toMillis());
        attributes.put("attempts", attempts);
        attributes.put("failureType", failureType);
        return AuditEvent.builder()
                .category(AuditCategories.OUTBOUND_CALL)
                .action(ACTION)
                .occurredAt(at)
                .actor(principal == null ? Actor.of(Actor.SYSTEM, SERVICE) : Actor.of(principal))
                .resource(new Resource(clientName, method, null))
                .outcome(switch (outcome) {
                    case SUCCESS -> AuditOutcome.success();
                    case NOT_PERMITTED -> AuditOutcome.denied(outcome);
                    default -> AuditOutcome.failure(failureType == null ? code : code + ": " + failureType);
                })
                .correlationId(correlationId)
                .attributes(attributes)
                .build();
    }
}
