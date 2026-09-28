package ru.ludwigandreas.restclient.spi;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.Resource;

/**
 * One retained record of an outbound call.
 *
 * <p>Everything in it is either non-identifying or deliberately chosen: there is no raw URI, no
 * request body, no response body and no header that was not named in
 * {@code audit.include-request-headers} / {@code audit.include-response-headers}. That is not
 * caution for its own sake - an audit store is retained for years and read by people who are not
 * on the on-call rota, and the cheapest way to never leak a token into it is to have no field that
 * could carry one.
 *
 * @param clientName    the named client
 * @param method        the HTTP method
 * @param uriTemplate   the URI template, never the expanded path
 * @param statusCode    the HTTP status, or 0 when no response was received
 * @param outcome       {@code SUCCESS}, {@code CLIENT_ERROR}, {@code SERVER_ERROR},
 *                      {@code TIMEOUT}, {@code CONNECTION_ERROR}, {@code AUTH_ERROR},
 *                      {@code NOT_PERMITTED} or {@code UNKNOWN}
 * @param duration      wall time of the whole logical call, retries included
 * @param attempts      attempts made
 * @param correlationId the platform correlation id
 * @param traceId       the W3C trace id, when a trace was sampled
 * @param principal     who caused the call - the authenticated subject, or {@code null} for work
 *                      with no user behind it
 * @param at            when the call started
 * @param headers       the allow-listed headers, request and response, prefixed {@code req.}/
 *                      {@code res.}
 * @param failureType   the exception class name when the call ended in one
 */
public record OutboundCallAudit(
        String clientName,
        String method,
        String uriTemplate,
        int statusCode,
        String outcome,
        Duration duration,
        int attempts,
        String correlationId,
        String traceId,
        String principal,
        Instant at,
        Map<String, List<String>> headers,
        String failureType) {

    public OutboundCallAudit {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** The action every outbound-call event is recorded under. */
    public static final String ACTION = "partner.called";

    /** What {@link Actor#principalType()} carries for a call with no user behind it. */
    public static final String PRINCIPAL_TYPE_SERVICE = "SERVICE";

    /**
     * This record as a platform audit event.
     *
     * <p>Kept as the authoring surface rather than replaced by the envelope: assembling thirteen positional
     * components out of a {@code Map<String, Object>} at the call site would be worse than this record, and
     * this record is where the argument about what an outbound-call trail may contain belongs.
     *
     * <p>The resource is the URI <em>template</em> and never the expanded path, which is this record's
     * existing rule and the reason it has no raw-URI component at all. {@code principal} becomes the actor
     * when there is one and {@link #PRINCIPAL_TYPE_SERVICE} otherwise, so a scheduled call and a
     * user-initiated one are distinguishable in the trail rather than both appearing as {@code system}.
     *
     * @return the event, with no body, no expanded URI and no header that was not allow-listed
     */
    public AuditEvent toAuditEvent() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("clientName", clientName);
        attributes.put("method", method);
        attributes.put("statusCode", statusCode);
        attributes.put("outcome", outcome);
        attributes.put("durationMs", duration == null ? null : duration.toMillis());
        attributes.put("attempts", attempts);
        attributes.put("failureType", failureType);
        if (!headers.isEmpty()) {
            attributes.put("headers", headers);
        }
        return AuditEvent.builder()
                .category(AuditCategories.OUTBOUND_CALL)
                .action(ACTION)
                .occurredAt(at)
                .actor(principal == null ? Actor.of(Actor.SYSTEM, PRINCIPAL_TYPE_SERVICE)
                        : Actor.of(principal))
                .resource(new Resource(clientName, uriTemplate, null))
                .outcome(outcomeOf())
                .correlationId(correlationId)
                .traceId(traceId)
                .attributes(attributes)
                .build();
    }

    /**
     * The outcome label as a platform outcome.
     *
     * <p>{@code NOT_PERMITTED} maps to {@link AuditOutcome.Status#DENIED} rather than to a failure: the
     * call was refused by this service's own policy and nothing broke, which is the distinction an
     * incident responder needs first.
     */
    private AuditOutcome outcomeOf() {
        if ("SUCCESS".equals(outcome)) {
            return AuditOutcome.success();
        }
        if ("NOT_PERMITTED".equals(outcome)) {
            return AuditOutcome.denied(outcome);
        }
        return AuditOutcome.failure(failureType == null ? outcome : outcome + ": " + failureType);
    }
}
