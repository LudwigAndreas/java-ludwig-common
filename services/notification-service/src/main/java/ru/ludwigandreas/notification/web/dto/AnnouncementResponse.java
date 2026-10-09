package ru.ludwigandreas.notification.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * One announcement on the wire.
 *
 * <p>The audience is <b>absent</b> from a recipient's view of an announcement, and that is not an
 * oversight. A recipient is already inside the audience by virtue of being shown it, so the field
 * could only tell them something about other people - and over time, told to enough recipients, it
 * maps which roles the platform addresses and therefore which roles exist. The same reasoning as the
 * audience columns carrying no {@code @Filterable}.
 *
 * <p>The administrative view returns {@link AnnouncementAdminResponse} instead, which does carry it.
 *
 * @param dismissed derived rather than stored, so a client never has to decide whether a null instant
 *                  means "not dismissed" or "field omitted from this projection"
 */
@Schema(name = "Announcement", description = "One announcement visible to the caller")
public record AnnouncementResponse(

        UUID id,

        String category,

        CategoryClassDto categoryClass,

        @Schema(description = "The language this content was rendered in")
        String locale,

        String subject,

        String bodyHtml,

        String bodyText,

        Instant visibleFrom,

        Instant visibleUntil,

        Instant publishedAt,

        boolean dismissed) {
}
