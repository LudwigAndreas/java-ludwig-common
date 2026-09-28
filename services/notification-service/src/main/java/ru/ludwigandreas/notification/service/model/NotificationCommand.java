package ru.ludwigandreas.notification.service.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one thing both ingress adapters converge on.
 *
 * <p>The Kafka listener and the REST controller each translate their own wire format into this and
 * then do nothing else: no validation beyond the transport's own, no resolution, no persistence. That
 * is what keeps "a notification request" one concept rather than two that drift - the moment an
 * adapter starts deciding something, the other adapter has to be taught the same rule, and one of
 * them eventually is not.
 *
 * <p>Carries a template key and a variable map, never rendered text. A caller that could submit a
 * body would be deciding this service's wording, would bypass localization entirely, and would turn
 * every template change into a coordinated release across every calling service.
 *
 * @param idempotencyKey   the caller's dedup key - the HTTP {@code Idempotency-Key} or the Kafka
 *                         record key. Absent means "send it, I accept a duplicate on a retry"
 * @param templateKey      template family, e.g. {@code password-reset}
 * @param category         business grouping preferences are expressed against, e.g. {@code security}
 * @param categoryClass    whether the recipient may decline it
 * @param priority         which lane
 * @param channels         channels to attempt; a recipient with no address for one of them simply
 *                         produces no delivery on it rather than failing the request
 * @param recipients       who to notify
 * @param variables        request-wide template variables
 * @param scheduledAt      earliest send time, or null for as soon as possible
 * @param source           which adapter this arrived through
 * @param correlationId    correlation id of the inbound record or request, carried onto every
 *                         delivery so the provider call and its log lines join back to the caller
 * @param traceId          sampled trace id, when there was one
 */
public record NotificationCommand(
        String idempotencyKey,
        String templateKey,
        String category,
        CategoryClass categoryClass,
        Priority priority,
        Set<ChannelType> channels,
        List<RecipientRef> recipients,
        Map<String, Object> variables,
        Instant scheduledAt,
        IngressSource source,
        String correlationId,
        String traceId) {

    public NotificationCommand {
        channels = channels == null ? Set.of() : Set.copyOf(channels);
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
