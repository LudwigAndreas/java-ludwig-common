package ru.ludwigandreas.notification.web.dto;

import java.time.LocalTime;
import java.util.UUID;

/**
 * A recipient's contact record as returned.
 *
 * <p>The three destinations are masked on the way out - see {@code NotificationDtoMapper}. An
 * operator confirming that the right address is on file can do so from {@code j***n@e***.com} while a
 * compromised support account cannot use this endpoint to enumerate the customer base.
 */
public record RecipientProfileResponse(
        UUID id,
        String userId,
        String emailAddress,
        String chatAddress,
        String webhookUrl,
        String locale,
        String timezone,
        LocalTime quietHoursStart,
        LocalTime quietHoursEnd) {
}
