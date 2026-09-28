package ru.ludwigandreas.notification.service.model;

import java.time.Instant;

/**
 * One entry of a delivery's status trail, as reported by the admin API.
 *
 * @param from null on the first entry, which has no predecessor
 */
public record DeliveryTransition(
        DeliveryState from,
        DeliveryState to,
        int attemptNumber,
        Instant occurredAt,
        String detail) {
}
