package ru.ludwigandreas.notification.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the ingress answers with.
 *
 * <p>The deliveries are included rather than left to a second call, because the caller's next
 * question is always "how many did that actually become?" - a request naming three recipients and
 * two channels can legitimately produce anything from zero to six, and which it was is the difference
 * between a working integration and a silent one.
 *
 * @param duplicate true when a previously used idempotency key mapped this call to an existing
 *                  request; nothing new was created and this describes the original
 */
public record NotificationRequestResponse(
        UUID id,
        String idempotencyKey,
        String templateKey,
        String category,
        CategoryClassDto categoryClass,
        PriorityDto priority,
        String state,
        Instant scheduledAt,
        Instant createdAt,
        String rejectionReason,
        List<DeliveryResponse> deliveries,
        boolean duplicate) {
}
