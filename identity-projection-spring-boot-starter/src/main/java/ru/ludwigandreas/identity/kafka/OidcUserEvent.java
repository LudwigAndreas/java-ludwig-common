package ru.ludwigandreas.identity.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Set;

/**
 * A user event as it arrives from the OIDC provider's Kafka topic.
 *
 * <p>Tolerant of unknown fields on purpose: the provider owns this schema and will add to it, and a
 * consumer that fails on a new field turns an upstream release into an outage in every service that
 * consumes the topic.
 *
 * @param eventId      producer's id for the event; used only for logging and de-duplication diagnostics
 * @param type         what happened
 * @param subject      the OIDC {@code sub} - the projection's primary key
 * @param displayName  human-readable label, stored for audit readability
 * @param tenantId     owning organization, in a multi-tenant deployment
 * @param roles        the user's <em>complete</em> role set, not a delta
 * @param email        the user's verified primary address, when the provider publishes contact data
 * @param alternateEmail a secondary address - an external one where {@code email} is internal, say
 * @param phoneNumber  the user's phone number in E.164 form
 * @param chatHandle   the user's handle on the organization's chat system
 * @param emailVerified whether the provider has verified {@code email}. {@code Boolean} rather than
 *                     {@code boolean} so that "the provider did not say" stays distinguishable from
 *                     "the provider said no" - a consumer that treated silence as unverified would
 *                     stop mailing everybody the moment an older producer omitted the field
 * @param phoneVerified whether the provider has verified {@code phoneNumber}
 * @param sourceVersion the directory's own version of the record, when it publishes one
 * @param occurredAt   when the change happened upstream - the ordering key the projection compares on,
 *                     because Kafka only orders within a partition and a user's events can be
 *                     repartitioned when the topic is scaled
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OidcUserEvent(
        String eventId,
        OidcUserEventType type,
        String subject,
        String displayName,
        String tenantId,
        Set<String> roles,
        String email,
        String alternateEmail,
        String phoneNumber,
        String chatHandle,
        Boolean emailVerified,
        Boolean phoneVerified,
        String sourceVersion,
        Instant occurredAt) {
}
