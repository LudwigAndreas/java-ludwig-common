package ru.ludwigandreas.notification.service.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the ingress answers with: the request that was accepted and the deliveries it fanned out to.
 *
 * <p>The deliveries are part of the answer rather than a second call because the caller's next
 * question is always "how many did that actually become?" - a request naming three recipients and
 * two channels can legitimately produce anything from zero to six deliveries, and which it was is
 * the difference between a working integration and a silent one.
 *
 * @param duplicate whether this response describes an existing request that a previously used
 *                  idempotency key mapped to, rather than one this call created
 */
public record NotificationRequestView(
        UUID id,
        String idempotencyKey,
        String templateKey,
        String category,
        CategoryClass categoryClass,
        Priority priority,
        RequestState state,
        Instant scheduledAt,
        Instant createdAt,
        String rejectionReason,
        List<DeliveryView> deliveries,
        boolean duplicate) {

    public NotificationRequestView {
        deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
    }
}
