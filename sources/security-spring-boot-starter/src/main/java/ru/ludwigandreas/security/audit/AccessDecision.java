package ru.ludwigandreas.security.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Builder;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.Resource;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * One authorization decision, in the form an auditor or an incident responder actually needs.
 *
 * <p>It records the decision, not the request: who, what resource, what action, what the scope came
 * out as, and - when the check was about one object - which object. Deliberately no request body, no
 * headers and no token: an audit trail is retained for years and read by people who are not entitled
 * to the payloads, so it must not become a second copy of the data it is guarding.
 *
 * @param subject       the principal's stable id - the field that makes the trail joinable
 * @param resourceId    the object a single-object check was about, or {@code null} for a query-level
 *                      decision where the answer was a predicate rather than a row
 * @param scopeAccess   {@code ALL}, {@code RESTRICTED} or {@code NONE} - how wide the grant turned out
 * @param credentialKind what the caller presented, when it was not the credential their identity was
 *                      established with. {@code null} for an ordinary request, which is most of them
 * @param credentialId  the long-lived credential's id - a personal access token id, never a key id and never
 *                      anything secret-adjacent. This is the field that turns "alice was denied" into "the
 *                      CI token alice minted was denied", which are different investigations
 *
 * <p>Kept as this module's authoring surface after the audit consolidation, rather than replaced by
 * {@link AuditEvent}: a caller assembling eight positional components out of a
 * {@code Map<String, Object>} is worse than this record, and this record is where the reasoning about
 * what an authorization decision may and may not contain belongs. {@link #toAuditEvent} flattens it
 * into the platform envelope, which is the transport and storage surface. This envelope was in fact
 * derived from this record, which was already the closest thing in the repository to a universal one.
 */
@Builder
public record AccessDecision(
        String subject,
        PrincipalType principalType,
        String resourceType,
        String action,
        String resourceId,
        String scopeAccess,
        String credentialKind,
        String credentialId,
        boolean granted,
        String reason) {

    /** The action name a denial without a resolvable method is recorded under. */
    public static final String ACTION_DENIED = "access.denied";

    /** The action name a granted decision is recorded under. */
    public static final String ACTION_GRANTED = "access.granted";

    /**
     * This decision as a platform audit event.
     *
     * <p>{@link AuditOutcome.Status#DENIED} rather than {@code FAILURE} for a refusal, which is the
     * distinction an incident responder needs first: "we refused them" and "we broke" look identical in
     * a {@code granted=false} column and lead to opposite investigations.
     *
     * <p>{@code scopeAccess} becomes an attribute rather than a component of the envelope, because
     * "how wide did the grant turn out" is a fact about this platform's data-scope model and not about
     * auditing in general. Nothing is dropped.
     *
     * @return the event, with no payload, no headers and no token - the rule this record already stated
     */
    public AuditEvent toAuditEvent() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (scopeAccess != null) {
            attributes.put("scopeAccess", scopeAccess);
        }
        // Recorded as attributes rather than as envelope components for the same reason scopeAccess is:
        // "what credential was presented" is a fact about this platform's authentication model, not about
        // auditing in general. The actor stays the human - they are who is accountable - and the credential
        // sits beside them, which is the distinction an investigation needs first.
        if (credentialKind != null) {
            attributes.put("credentialKind", credentialKind);
        }
        if (credentialId != null) {
            attributes.put("credentialId", credentialId);
        }
        return AuditEvent.builder()
                .category(AuditCategories.ACCESS)
                .action(granted ? ACTION_GRANTED : ACTION_DENIED)
                .actor(new Actor(subject, principalType == null ? null : principalType.name(), null, null))
                .resource(new Resource(resourceType, resourceId, null))
                .outcome(granted ? AuditOutcome.success() : AuditOutcome.denied(reason))
                .attributes(attributes)
                .build();
    }
}
