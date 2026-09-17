package ru.ludwigandreas.notification.service.model;

import java.time.LocalTime;
import java.util.UUID;

/**
 * A recipient's contact record as the business layer reports it.
 *
 * <p>Addresses are present here because this is the model the admin endpoint that manages them
 * works in. The web layer masks them on the way out - see {@code NotificationDtoMapper} - so the
 * full value never leaves the process except to the one endpoint entitled to it.
 */
public record RecipientProfileView(
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
