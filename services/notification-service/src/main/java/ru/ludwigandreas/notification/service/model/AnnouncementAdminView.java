package ru.ludwigandreas.notification.service.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One announcement as its publisher sees it: the audience included.
 *
 * <p>A service-model type rather than the entity, because a controller must not handle a JPA entity -
 * the layering rules fail the build on it, and they are right to: an entity in a controller signature
 * ties the published API to a column, so a rename becomes a breaking change and a lazy association
 * becomes a serialisation bug.
 *
 * <p>Distinct from {@link AnnouncementView}, which recipients get, and the difference is the audience:
 * a recipient is inside it already, so telling them what it is only describes other people. One type
 * with a sometimes-populated audience would be one line away from leaking it.
 */
public record AnnouncementAdminView(
        UUID id,
        String category,
        CategoryClass categoryClass,
        AudienceType audienceType,
        String audienceValue,
        String templateKey,
        Instant visibleFrom,
        Instant visibleUntil,
        Instant publishedAt,
        String publishedBy) {
}
