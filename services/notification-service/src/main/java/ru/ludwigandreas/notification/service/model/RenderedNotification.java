package ru.ludwigandreas.notification.service.model;

import java.util.Map;
import java.util.UUID;

/**
 * Everything a channel needs to send one message, and nothing it does not.
 *
 * <p>A channel receives this and never touches the database, the delivery entity or the request. That
 * is deliberate: it is what makes a channel unit-testable without a persistence context, and it is
 * what guarantees no channel can hold a transaction open across a network call, because it has
 * nothing transactional to hold.
 *
 * @param deliveryId    the delivery this render belongs to, used as the provider-side idempotency
 *                      key where the provider supports one
 * @param address       the resolved destination
 * @param subject       rendered subject; null on channels with no notion of one
 * @param htmlBody      rendered HTML part, or the JSON body for chat and webhook
 * @param textBody      rendered plain-text part; always present for email
 * @param headers       channel-specific metadata (chat room, webhook event name)
 * @param correlationId carried from the inbound request onto the outbound provider call
 */
public record RenderedNotification(
        UUID deliveryId,
        ChannelType channel,
        String address,
        String recipientLocale,
        String subject,
        String htmlBody,
        String textBody,
        String templateKey,
        String category,
        Map<String, String> headers,
        String correlationId) {

    public RenderedNotification {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
