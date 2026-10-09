package ru.ludwigandreas.notification.service.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One announcement as a recipient sees it, in their own language.
 *
 * <p>No audience field. A recipient is inside the audience by virtue of being shown this, so the
 * field could only describe other people - and over enough announcements it maps which roles the
 * platform addresses. The administrative view carries it; this one must not.
 */
public record AnnouncementView(
        UUID id,
        String category,
        CategoryClass categoryClass,
        String locale,
        String subject,
        String bodyHtml,
        String bodyText,
        Instant visibleFrom,
        Instant visibleUntil,
        Instant publishedAt,
        boolean dismissed) {
}
