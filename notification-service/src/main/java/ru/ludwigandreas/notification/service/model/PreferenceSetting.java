package ru.ludwigandreas.notification.service.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One stored preference.
 *
 * @param category the category declined or allowed, or {@code *} for every declinable category
 * @param channel  the channel it applies to, or null for all of them
 * @param allowed  false is an opt-out; true is an explicit opt-in that beats a wildcard opt-out
 */
public record PreferenceSetting(
        UUID id,
        String userId,
        String category,
        ChannelType channel,
        boolean allowed,
        String source,
        Instant updatedAt) {
}
