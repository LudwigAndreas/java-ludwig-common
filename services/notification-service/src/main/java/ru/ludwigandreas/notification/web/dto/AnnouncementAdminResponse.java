package ru.ludwigandreas.notification.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * One announcement as its publisher sees it: the audience included.
 *
 * <p>A separate type from {@link AnnouncementResponse} rather than the same one with nullable fields,
 * because the difference is who may see the audience. One type with an audience that is sometimes
 * populated is one field away from being populated on the recipient's endpoint by mistake, and that
 * mistake would leak which roles the platform addresses to everybody it announces to.
 *
 * @param emailRunId the fan-out operation started by this publish, when its category sends email;
 *                   null otherwise, because there is then no operation to poll
 */
@Schema(name = "AnnouncementAdmin",
        description = "One announcement as its publisher sees it, audience included")
public record AnnouncementAdminResponse(

        UUID id,

        String category,

        CategoryClassDto categoryClass,

        AudienceTypeDto audienceType,

        String audienceValue,

        String templateKey,

        Instant visibleFrom,

        Instant visibleUntil,

        Instant publishedAt,

        String publishedBy,

        UUID emailRunId) {
}
