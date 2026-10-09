package ru.ludwigandreas.notification.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * One inbox item on the wire.
 *
 * <p><b>No owner field, and that is a contract decision rather than an omission.</b> The owner is
 * always the authenticated caller, so a field carrying it could only ever repeat what the client
 * already knows - and a field on a response is an invitation to send it back, which is exactly the
 * parameter this API must never accept.
 *
 * <p>The body fields are null on a list and on the response to a transition, and populated when one
 * item is fetched. That asymmetry is deliberate: a page of fifty notifications must not carry fifty
 * bodies the client is about to discard, which is the reason content lives in its own table.
 *
 * @param read      derived from {@code readAt}, so a client never has to decide whether a null
 *                  instant means unread or means the field was omitted from this projection
 * @param dismissed derived from {@code dismissedAt} for the same reason
 */
@Schema(name = "InboxItem", description = "One notification in the caller's own inbox")
public record InboxItemResponse(

        UUID id,

        @Schema(description = "The notification catalogue key this was rendered from")
        String templateKey,

        String category,

        CategoryClassDto categoryClass,

        PriorityDto priority,

        @Schema(description = "The language the content was rendered in when it was produced")
        String locale,

        @Schema(description = "Null on a list; present when one item is fetched")
        String subject,

        @Schema(description = "Null on a list; present when one item is fetched")
        String bodyHtml,

        @Schema(description = "Null on a list; present when one item is fetched")
        String bodyText,

        Instant createdAt,

        @Schema(description = "When the caller was shown this in a list. Set once, never reset.")
        Instant seenAt,

        @Schema(description = "When the caller read it. Set once, never reset - it is the instant the "
                + "retention window is measured from.")
        Instant readAt,

        @Schema(description = "When the caller dismissed it. Dismissed items leave the default list "
                + "but remain retrievable by id.")
        Instant dismissedAt,

        boolean read,

        boolean dismissed) {
}
