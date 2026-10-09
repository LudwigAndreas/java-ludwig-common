package ru.ludwigandreas.notification.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How many announcements the caller has not dismissed.
 *
 * <p>An object rather than a bare number so the endpoint can gain a field later without a breaking
 * change, for the same reason {@code InboxSummaryResponse} is one. Separate from the inbox's count
 * because the two feeds are separate: a client renders a bell badge from one and a banner from the
 * other, and summing them is the client's decision rather than this service's.
 *
 * @param outstanding visible announcements this caller has not dismissed
 */
@Schema(name = "AnnouncementSummary", description = "The caller's outstanding announcement count")
public record AnnouncementSummaryResponse(long outstanding) {
}
