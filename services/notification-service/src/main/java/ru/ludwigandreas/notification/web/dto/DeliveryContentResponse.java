package ru.ludwigandreas.notification.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The rendered body of one delivery.
 *
 * <p>Returned only by the endpoint that exists to inspect it, which is authorized separately from the
 * delivery listing and logs each read - "support looked at what we sent that customer" is exactly the
 * kind of access an audit trail is for. Subject to the shortest retention window in the service, so
 * an older delivery answers 410 rather than 404.
 */
public record DeliveryContentResponse(
        UUID deliveryId,
        String subject,
        String htmlBody,
        String textBody,
        Instant renderedAt,
        String templateVersion) {
}
