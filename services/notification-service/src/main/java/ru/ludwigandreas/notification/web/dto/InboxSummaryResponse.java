package ru.ludwigandreas.notification.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How many of the caller's notifications are outstanding.
 *
 * <p>An object rather than a bare number so the endpoint can gain a field - a per-category
 * breakdown, a bounded "99+" form - without a breaking change. An endpoint that returns a naked
 * integer has no second version.
 *
 * @param unread items that are neither read nor dismissed. A notification the recipient swiped away
 *               without opening counts as dealt with, because otherwise the badge could never be
 *               cleared
 */
@Schema(name = "InboxSummary", description = "The caller's outstanding notification count")
public record InboxSummaryResponse(long unread) {
}
