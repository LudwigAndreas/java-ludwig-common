package ru.ludwigandreas.notification.service.model;

import java.time.Instant;
import java.util.UUID;

/** One entry of the suppression list. */
public record SuppressionView(
        UUID id,
        ChannelType channel,
        String address,
        String reason,
        String detail,
        Instant createdAt,
        Instant expiresAt) {
}
