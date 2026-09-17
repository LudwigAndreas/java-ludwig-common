package ru.ludwigandreas.notification.web.dto;

import java.time.Instant;
import java.util.UUID;

/** One stored preference. */
public record PreferenceResponse(
        UUID id,
        String userId,
        String category,
        ChannelTypeDto channel,
        boolean allowed,
        String source,
        Instant updatedAt) {
}
