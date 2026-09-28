package ru.ludwigandreas.notification.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One delivery as clients and operators see it.
 *
 * <p>Neither the recipient's address nor the rendered body appears here, and that is structural
 * rather than a filter somebody remembered to apply: they are not on the model this response is
 * mapped from either. The body is available from its own endpoint, which is authorized separately and
 * logs the access.
 *
 * @param recipientReference {@code user:<subject>} or {@code address:<masked>} - enough to tell two
 *                           deliveries of one request apart without naming anybody
 * @param templateVersion    content hash of the template that rendered it; quote it to an operator
 *                           to establish exactly what text was sent
 */
public record DeliveryResponse(
        UUID id,
        UUID requestId,
        ChannelTypeDto channel,
        String recipientReference,
        String templateKey,
        String templateVersion,
        String category,
        CategoryClassDto categoryClass,
        PriorityDto priority,
        DeliveryStatusDto status,
        int attempts,
        int maxAttempts,
        Instant nextAttemptAt,
        Instant scheduledAt,
        Instant sentAt,
        Instant deliveredAt,
        String lastError,
        FailureClassDto lastFailureClass,
        String suppressionReason,
        String providerMessageId,
        Instant createdAt) {
}
