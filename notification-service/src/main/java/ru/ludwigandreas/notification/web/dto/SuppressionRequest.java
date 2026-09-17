package ru.ludwigandreas.notification.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Adds a destination to the suppression list by hand.
 *
 * @param expiresAt when the suppression lapses; omit for permanent. A soft bounce should expire and
 *                  a spam complaint should not, and encoding that here rather than in the purge job
 *                  means the distinction survives a change of retention policy
 */
public record SuppressionRequest(

        @NotNull(message = "{notification.validation.channel.required}")
        ChannelTypeDto channel,

        @NotBlank(message = "{notification.validation.suppression.address.required}")
        @Size(max = 512, message = "{notification.validation.recipient.address.size}")
        String address,

        @NotBlank(message = "{notification.validation.suppression.reason.required}")
        @Size(max = 64, message = "{notification.validation.suppression.reason.size}")
        String reason,

        @Size(max = 2000, message = "{notification.validation.suppression.detail.size}")
        String detail,

        Instant expiresAt) {
}
