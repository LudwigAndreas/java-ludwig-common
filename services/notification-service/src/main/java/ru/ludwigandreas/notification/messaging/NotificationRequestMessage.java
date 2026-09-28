package ru.ludwigandreas.notification.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A notification request as it arrives on the topic.
 *
 * <p>A separate type from the REST {@code SendNotificationRequest} on purpose, and the architecture
 * rules enforce it ({@code kafka.payloads-are-not-rest-dtos}). A topic and an HTTP endpoint are
 * versioned on different clocks by different people: the REST contract can be changed with a
 * deprecation window and a client release, and the topic contract has to stay readable by every
 * producer that has not been redeployed. Sharing one class means a change that is safe for one is
 * silently forced on the other.
 *
 * <p>Tolerant of unknown fields, because producers add to their payloads and a consumer that fails on
 * a new field turns somebody else's release into an outage here.
 *
 * @param idempotencyKey the producer's dedup key. Also carried in the record key by convention, but
 *                       read from the payload so a producer that does not control its key can still
 *                       be idempotent
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationRequestMessage(
        String idempotencyKey,
        String templateKey,
        String category,
        String categoryClass,
        String priority,
        Set<String> channels,
        List<RecipientMessage> recipients,
        Map<String, Object> variables,
        Instant scheduledAt) {

    /**
     * One recipient on the wire.
     *
     * @param userId  the OIDC subject; the form to prefer
     * @param address a literal destination, for recipients with no account
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecipientMessage(
            String userId,
            String address,
            String locale,
            String timezone,
            Map<String, Object> variables) {
    }
}
