package ru.ludwigandreas.notification.service.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One delivery as the business layer reports it.
 *
 * <p>Note what is absent: the recipient's address and the rendered body. Both exist on the entity
 * and both are personal data, so they are fetched deliberately, by an endpoint that says so and
 * audits it, rather than riding along in every listing. A model that carried them would put them in
 * every log line that logged a result and every cache that held one.
 *
 * @param recipientReference {@code user:<subject>} or {@code address:<masked>} - enough to tell two
 *                           deliveries of one request apart without naming anybody
 */
public record DeliveryView(
        UUID id,
        UUID requestId,
        ChannelType channel,
        String recipientReference,
        String templateKey,
        String templateVersion,
        String category,
        CategoryClass categoryClass,
        Priority priority,
        DeliveryState state,
        int attempts,
        int maxAttempts,
        Instant nextAttemptAt,
        Instant scheduledAt,
        Instant sentAt,
        Instant deliveredAt,
        String lastError,
        FailureClass lastFailureClass,
        String suppressionReason,
        String providerMessageId,
        Instant createdAt) {
}
