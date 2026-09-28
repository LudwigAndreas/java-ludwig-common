package ru.ludwigandreas.notification.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.ludwigandreas.webcore.operation.OperationResponse;

/**
 * What the ingress answers with.
 *
 * <p>The deliveries are included rather than left to a second call, because the caller's next
 * question is always "how many did that actually become?" - a request naming three recipients and
 * two channels can legitimately produce anything from zero to six, and which it was is the difference
 * between a working integration and a silent one.
 *
 * <h2>The platform envelope is added, nothing is taken away</h2>
 *
 * <p>{@code operation} is {@code web-core}'s envelope for a long-running operation, carried
 * <em>alongside</em> the members this response already published rather than instead of them. This
 * response is a published API with consumers outside this repository, so replacing {@code state},
 * {@code createdAt} or {@code rejectionReason} would be a versioned API change; adding a member is
 * not, and every existing client keeps reading exactly what it read before.
 *
 * <p>What the envelope buys is a shape that is the same for every long-running operation on this
 * platform, so a caller that submits a report and a notification polls them with one piece of code.
 * {@code state} and {@code operation.detail} carry the same word and cannot drift - both are
 * projected from the same view in one mapping.
 *
 * @param operation the request in the platform's long-running-operation envelope
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
        OperationResponse operation,
        boolean duplicate) {

}
