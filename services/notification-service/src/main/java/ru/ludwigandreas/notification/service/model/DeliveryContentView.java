package ru.ludwigandreas.notification.service.model;

import java.time.Instant;
import java.util.UUID;

/**
 * The rendered body of one delivery, returned only by the endpoint that exists to inspect it.
 *
 * <p>Reading this is an audited act: it is the single most sensitive read this service offers, and
 * "support looked at what we sent that customer" is exactly the kind of access an audit trail is for.
 */
public record DeliveryContentView(
        UUID deliveryId,
        String subject,
        String htmlBody,
        String textBody,
        Instant renderedAt,
        String templateVersion) {
}
