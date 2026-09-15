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
        String sourceVersion,
        Instant occurredAt) {
}
