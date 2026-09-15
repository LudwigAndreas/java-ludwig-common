package ru.ludwigandreas.security.audit;

import lombok.Builder;
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
 */
@Builder
public record AccessDecision(
        String subject,
        PrincipalType principalType,
        String resourceType,
        String action,
        String resourceId,
        String scopeAccess,
        boolean granted,
        String reason) {
}
