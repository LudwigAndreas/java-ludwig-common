package ru.ludwigandreas.notification.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

/**
 * A recipient's contact record.
 *
 * <p>Written by the user-profile service when its own data changes, and by support. It carries what
 * the identity projection deliberately does not: where to reach somebody, in what language, and when
 * not to. See {@code DefaultRecipientResolver} for why that split exists.
 *
 * @param quietHoursStart start of the recipient's quiet window, in {@code timezone}. A window that
 *                        wraps midnight - 22:00 to 07:00 - is the normal case and is handled
 */
public record RecipientProfileRequest(

        @Email(message = "{notification.validation.profile.email.invalid}")
        @Size(max = 320, message = "{notification.validation.profile.email.size}")
        String emailAddress,

        @Size(max = 255, message = "{notification.validation.profile.chat.size}")
        String chatAddress,

        @Size(max = 1024, message = "{notification.validation.profile.webhook.size}")
        String webhookUrl,

        @Size(max = 35, message = "{notification.validation.recipient.locale.size}")
        String locale,

        @Size(max = 64, message = "{notification.validation.recipient.timezone.size}")
        String timezone,

        LocalTime quietHoursStart,

        LocalTime quietHoursEnd) {
}
