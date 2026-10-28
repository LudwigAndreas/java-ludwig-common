package ru.ludwigandreas.notification.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * The new visibility window of a published announcement, replacing the old one whole.
 *
 * @param visibleFrom  omitted keeps the current start; changeable only before the window opens
 * @param visibleUntil mandatory, and after now - taking an announcement down now is a withdrawal
 */
@Schema(name = "ChangeAnnouncementWindowRequest",
        description = "Replace an announcement's visibility window. The start can move only before "
                + "the window opens; the end cannot be in the past.")
public record ChangeAnnouncementWindowRequest(

        Instant visibleFrom,

        @NotNull(message = "{notification.validation.announcement.visible-until.required}")
        Instant visibleUntil) {
}
