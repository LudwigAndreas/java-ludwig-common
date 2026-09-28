package ru.ludwigandreas.usersettings.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;
import ru.ludwigandreas.usersettings.api.ConsentDecision;

/**
 * One consent decision, as the owner recorded it.
 *
 * <p>Published under two event types - {@code ConsentGranted} and {@code ConsentRevoked} - carrying
 * the same payload shape. Two types rather than one because a consumer usually cares about only one
 * of them (a marketing service reacts to revocation and can ignore grants), and a single type would
 * force every consumer to deserialize and branch on a field to find that out.
 *
 * <p>{@link #consentId()} is the owner's primary key and is reused verbatim by every projection, so
 * a redelivered event collides on the key instead of appending a second copy of the same decision.
 * That is the whole idempotency mechanism for consents: there is no de-duplication table and no
 * upsert, because a consent row is never updated.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConsentChangedEvent(
        String eventId,
        UUID consentId,
        String tenantId,
        String subject,
        String consentKey,
        String textVersion,
        ConsentDecision decision,
        String locale,
        Instant occurredAt,
        String actor,
        String evidenceIp,
        String evidenceUserAgent,
        String correlationId) {
}
