package ru.ludwigandreas.notification.web.dto;

import java.time.Instant;

/**
 * One entry of a delivery's status trail.
 *
 * <p>Current status answers none of the questions an operator actually asks - how long did it sit
 * pending, how many times did it fail before it died, was it suppressed before or after the recipient
 * unsubscribed. This is what answers them.
 */
public record DeliveryTransitionResponse(
        DeliveryStatusDto from,
        DeliveryStatusDto to,
        int attemptNumber,
        Instant occurredAt,
        String detail) {
}
