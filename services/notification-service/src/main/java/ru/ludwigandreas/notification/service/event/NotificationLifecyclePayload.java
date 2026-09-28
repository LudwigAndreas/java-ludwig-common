package ru.ludwigandreas.notification.service.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Body of every lifecycle event this service publishes.
 *
 * <p>An explicit, stable contract rather than a serialized entity, so an internal column rename
 * cannot silently reshape what a dozen subscribers receive.
 *
 * <p><b>It carries no address and no rendered content.</b> The temptation is real - a subscriber
 * reacting to a bounce would find the address convenient - and it is exactly how personal data ends
 * up on a Kafka topic with a long retention, replicated into every consumer's own store, outside
 * this service's retention policy and beyond the reach of its purge job. A subscriber that genuinely
 * needs the destination can ask the admin API for it, authenticated and audited.
 *
 * @param deliveryId  the delivery this is about; the id a subscriber correlates on
 * @param requestId   the request it fanned out from, so a caller can match it to its own ask
 * @param recipientId the subject, when the recipient was addressed by user id - pseudonymous, and
 *                    absent entirely for a literal address
 * @param reason      why, for the failed and suppressed events; a closed-set code, not a sentence
 */
public record NotificationLifecyclePayload(
        UUID deliveryId,
        UUID requestId,
        String templateKey,
        String category,
        String channel,
        String recipientId,
        String state,
        String reason,
        int attempts,
        Instant occurredAt) {
}
