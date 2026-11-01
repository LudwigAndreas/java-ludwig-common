package ru.ludwigandreas.identity.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Set;
import org.springframework.messaging.converter.MessageConversionException;
import ru.ludwigandreas.identity.feed.IdentityChange;
import ru.ludwigandreas.identity.feed.UserProfile;

/**
 * Version 1 of the published user-change event a provider writes to a subscribed topic.
 *
 * <p>Fields the contract does not name are ignored ({@link JsonIgnoreProperties#ignoreUnknown()}): the provider owns
 * the schema, and a consumer that failed on a new field would turn an upstream release into an outage in every
 * service consuming the topic. The ordering key is {@code occurredAt}, because Kafka orders only within a partition
 * and a user's events can move between partitions when a topic is scaled.
 *
 * @param eventId        the producer's id for the event; used only in logs
 * @param type           what happened
 * @param subject        the user's subject at the provider - the projection's key
 * @param displayName    human-readable label
 * @param tenantId       owning organisation, in a multi-tenant deployment
 * @param roles          the user's <em>complete</em> role set, never a delta; absent keeps the stored roles
 * @param email          the verified primary address, when the provider publishes contact data
 * @param alternateEmail a secondary address
 * @param phoneNumber    the phone number in E.164 form
 * @param chatHandle     the handle on the organisation's chat system
 * @param emailVerified  whether the provider verified {@code email}; null means it did not say
 * @param phoneVerified  whether the provider verified {@code phoneNumber}
 * @param sourceVersion  the provider's own version of the record, when it publishes one
 * @param occurredAt     when the change happened at the provider
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IdentityUserEvent(String eventId, IdentityUserEventType type, String subject, String displayName,
                                String tenantId, Set<String> roles, String email, String alternateEmail,
                                String phoneNumber, String chatHandle, Boolean emailVerified, Boolean phoneVerified,
                                String sourceVersion, Instant occurredAt) {

    /**
     * The change this event states, for one source.
     *
     * @param source the source the event's topic is subscribed for
     * @return the change
     * @throws MessageConversionException when the event lacks its type or time - it will never become valid, so the
     *                                    platform's consumer dead-letters it at once
     */
    public IdentityChange toChange(String source) {
        if (type == null || occurredAt == null) {
            throw new MessageConversionException("Identity user event " + eventId + " has no "
                    + (type == null ? "type" : "occurredAt"));
        }
        return switch (type) {
            case UPSERT -> new IdentityChange.Upsert(source, subject, occurredAt, sourceVersion,
                    new UserProfile(displayName, tenantId, roles, new UserProfile.Contact(email, emailVerified,
                            alternateEmail, phoneNumber, phoneVerified, chatHandle)));
            case DISABLE -> new IdentityChange.Disable(source, subject, occurredAt, sourceVersion);
            case DELETE -> new IdentityChange.Remove(source, subject, occurredAt, sourceVersion);
        };
    }
}
