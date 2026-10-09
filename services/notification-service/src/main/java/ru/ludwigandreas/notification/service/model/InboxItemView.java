package ru.ludwigandreas.notification.service.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One inbox item as the business layer sees it, content included.
 *
 * <p>Content is on the view although it is a separate table, because every caller that wants one
 * item wants its body - the split exists so that the <em>list</em> and the <em>count</em> do not
 * carry bodies, not so that a single read needs two round trips.
 *
 * <p>No owner field. The owner is resolved from the authenticated caller on the way in and is
 * already known to be the caller on the way out, so carrying it would add a field whose only
 * possible use is to be compared against the value it came from - and whose presence on a response
 * would invite a client to send it back.
 *
 * @param read      derived rather than stored, so a client never has to decide whether a null
 *                  instant means unread or means the field was omitted
 * @param dismissed derived for the same reason
 */
public record InboxItemView(
        UUID id,
        String templateKey,
        String category,
        CategoryClass categoryClass,
        Priority priority,
        String locale,
        String subject,
        String bodyHtml,
        String bodyText,
        Instant createdAt,
        Instant seenAt,
        Instant readAt,
        Instant dismissedAt,
        boolean read,
        boolean dismissed) {
}
