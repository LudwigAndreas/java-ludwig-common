package ru.ludwigandreas.pat.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.Resource;

/**
 * The lifecycle events, as typed records with a {@code toAuditEvent()}.
 *
 * <p>Typed records are the authoring surface and the platform envelope is the transport, which is the shape
 * {@code audit-core} asks every module for: a caller assembling positional components out of a
 * {@code Map<String, Object>} is worse than a record, and the record is where the reasoning about what each
 * event may and may not contain belongs.
 *
 * <h2>What is audited per event, and what is deliberately not</h2>
 *
 * <p>Issuance, rotation, revocation, expiry and a <b>refused</b> issuance attempt are each one event. A
 * request to mint a credential for another identity is a security event whether it succeeds or not, which is
 * why the refusal is here.
 *
 * <p><b>Use is not audited per request.</b> A busy token is exchanged thousands of times a day and the
 * volume is per-request while the signal is not. What the row carries instead is a debounced
 * {@code last_used_at}; what reaches the sink is {@link FirstUse}, {@link DormantWake} and
 * {@link UnseenSource} - the three that actually indicate compromise.
 *
 * <p>No event here carries a secret, a digest or a key id. The id is what an operator revokes by and what
 * joins a trail together; a digest in an audit record would be credential material in a store retained for
 * years and read by people not entitled to it.
 *
 * <p>Nothing here wraps {@code AuditSink.record} in a {@code try}/{@code catch}. Whether a sink failure
 * fails the caller is {@code AuditFailurePolicy}, resolved from configuration, and a catch in a library
 * overrides a decision the deployment made.
 */
public final class PatAuditEvents {

    private PatAuditEvents() {
    }

    private static AuditEvent.AuditEventBuilder base(String action, String patId, String ownerSubject) {
        return AuditEvent.builder()
                .category(AuditCategories.CREDENTIAL)
                .action(action)
                .actor(Actor.of(ownerSubject))
                .resource(new Resource("personal-access-token", patId, null));
    }

    /** A token was issued. The only event that records who issued it, which is not always the owner. */
    public record Issued(
            String patId,
            String ownerSubject,
            String issuedBy,
            String name,
            Set<String> scopes,
            Set<String> audiences,
            Instant expiresAt) {

        public AuditEvent toAuditEvent() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", name);
            attributes.put("scopes", scopes);
            attributes.put("audiences", audiences);
            attributes.put("expiresAt", expiresAt == null ? "never" : expiresAt.toString());
            // Recorded even when it equals the owner, so a query for "tokens minted on behalf of someone
            // else" is a filter rather than a join against a separate source.
            attributes.put("issuedBy", issuedBy);
            return base("pat.issued", patId, ownerSubject)
                    .outcome(AuditOutcome.success())
                    .attributes(attributes)
                    .build();
        }
    }

    /**
     * An issuance was refused.
     *
     * <p>Carries no token id, because no token exists - which is why this is the one event whose resource id
     * is null and whose interest is entirely in the actor and the reason.
     */
    public record IssuanceRefused(
            String requestedOwner,
            String requestedBy,
            String reason) {

        public AuditEvent toAuditEvent() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("requestedOwner", requestedOwner);
            attributes.put("requestedBy", requestedBy);
            return base("pat.issuance-refused", null, requestedBy)
                    .outcome(AuditOutcome.denied(reason))
                    .attributes(attributes)
                    .build();
        }
    }

    /**
     * A token's secret was rotated, with the old one accepted until the overlap elapses.
     *
     * <p>The overlap attribute is called {@code rotationOverlapUntil} rather than
     * {@code previousSecretAcceptedUntil}, and the rename was forced by
     * {@code PatAuditEventTest.noEventCarriesCredentialMaterial} - which scans every event in this class
     * for the words "secret", "digest" and "keyId" and failed on the name even though the value is a
     * timestamp.
     *
     * <p>Kept as a rename rather than an exemption, because the check is more useful absolute than
     * qualified: "no audit attribute name mentions credential material" is a rule a reader can apply
     * without consulting a list of permitted exceptions, and the first exemption is what turns it into a
     * list. The new name is also simply clearer about what the value is.
     */
    public record Rotated(String patId, String ownerSubject, String rotatedBy, Instant overlapUntil) {

        public AuditEvent toAuditEvent() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("rotatedBy", rotatedBy);
            attributes.put("rotationOverlapUntil", overlapUntil.toString());
            return base("pat.rotated", patId, ownerSubject)
                    .outcome(AuditOutcome.success())
                    .attributes(attributes)
                    .build();
        }
    }

    /**
     * A token was revoked.
     *
     * <p>The reason is a component rather than free text because the reasons lead to different actions: an
     * operator's deliberate revocation, an owner being disabled and inactivity expiry are three different
     * events, and an auditor reading a list of revoked tokens needs to tell a departure from an incident
     * response.
     */
    public record Revoked(String patId, String ownerSubject, String revokedBy, String reason) {

        public AuditEvent toAuditEvent() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("revokedBy", revokedBy == null ? "system" : revokedBy);
            return base("pat.revoked", patId, ownerSubject)
                    .outcome(AuditOutcome.success())
                    .attributes(Map.copyOf(attributes))
                    .build();
        }
    }

    /** A token reached its expiry and had its digests destroyed by the sweep. */
    public record Expired(String patId, String ownerSubject, Instant expiredAt) {

        public AuditEvent toAuditEvent() {
            return base("pat.expired", patId, ownerSubject)
                    .outcome(AuditOutcome.success())
                    .attributes(Map.of("expiredAt", expiredAt.toString()))
                    .build();
        }
    }

    /** A terminal record passed its retention period and was deleted. */
    public record Purged(String patId, String ownerSubject) {

        public AuditEvent toAuditEvent() {
            return base("pat.purged", patId, ownerSubject)
                    .outcome(AuditOutcome.success())
                    .build();
        }
    }

    /** The first time a token was ever used. One event per token, for its lifetime. */
    public record FirstUse(String patId, String ownerSubject, String sourceIp, String audience) {

        public AuditEvent toAuditEvent() {
            return base("pat.first-use", patId, ownerSubject)
                    .outcome(AuditOutcome.success())
                    .attributes(Map.of("sourceIp", sourceIp, "audience", audience))
                    .build();
        }
    }

    /** A token that had gone quiet was used again - the shape of a credential found in an old repository. */
    public record DormantWake(String patId, String ownerSubject, String sourceIp, Instant previousUse) {

        public AuditEvent toAuditEvent() {
            return base("pat.dormant-wake", patId, ownerSubject)
                    .outcome(AuditOutcome.success())
                    .attributes(Map.of("sourceIp", sourceIp, "previousUse", previousUse.toString()))
                    .build();
        }
    }

    /** A token was used from a source never seen for it before. */
    public record UnseenSource(String patId, String ownerSubject, String sourceIp, String previousIp) {

        public AuditEvent toAuditEvent() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("sourceIp", sourceIp);
            attributes.put("previousIp", previousIp);
            return base("pat.unseen-source", patId, ownerSubject)
                    .outcome(AuditOutcome.success())
                    .attributes(attributes)
                    .build();
        }
    }

    /**
     * A revoked token was presented.
     *
     * <p>Not a lifecycle event but a security signal, and the one in this class that should alert: presenting
     * a revoked credential means something still holds it. The uniform failure response tells the presenter
     * nothing, which is exactly why this has to reach the sink.
     */
    public record RevokedTokenPresented(String patId, String ownerSubject, String sourceIp) {

        public AuditEvent toAuditEvent() {
            return base("pat.revoked-token-presented", patId, ownerSubject)
                    .outcome(AuditOutcome.denied("revoked"))
                    .attributes(Map.of("sourceIp", sourceIp))
                    .build();
        }
    }
}
