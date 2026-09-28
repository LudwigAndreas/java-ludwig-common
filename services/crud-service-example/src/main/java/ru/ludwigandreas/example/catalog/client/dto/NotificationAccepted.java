package ru.ludwigandreas.example.catalog.client.dto;

import java.util.UUID;

/**
 * What comes back from a submitted request.
 *
 * <p>Deliberately a subset: the notification service's own response also carries the fanned-out
 * deliveries, their states and a rejection reason, none of which this service does anything with. The
 * client's object mapper reads tolerantly, so the extra fields are ignored rather than fatal - which
 * is the whole basis of a consumer-owned contract copy surviving the other side adding a field.
 *
 * @param id        the request id, which is what makes the 202 answerable later
 * @param state     the notification service's own state name
 * @param duplicate true when the idempotency key matched an earlier submission, so this call changed
 *                  nothing - the distinction a retry after a timeout needs
 */
public record NotificationAccepted(UUID id, String state, boolean duplicate) {
}
