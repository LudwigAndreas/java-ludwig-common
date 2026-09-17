package ru.ludwigandreas.notification.web.dto;

import java.time.Instant;
import java.util.UUID;

/** One entry of the suppression list; the address is masked on the way out. */
public record SuppressionResponse(
        UUID id,
        ChannelTypeDto channel,
        String address,
        String reason,
        String detail,
        Instant createdAt,
        Instant expiresAt) {
}
