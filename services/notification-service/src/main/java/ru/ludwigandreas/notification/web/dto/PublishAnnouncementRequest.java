package ru.ludwigandreas.notification.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

/**
 * What an announcer sends to publish one announcement.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>There is no {@code channels} field, no {@code alsoEmail} flag and no {@code categoryClass}. All
 * three are decided by the <b>category</b>, out of a catalogue this deployment configures, and the
 * reason is the argument {@code PreferenceEvaluator} already makes about the transactional bypass: a
 * field an announcer can set is a field every announcer sets to the most permissive value, because
 * from inside any one team its own announcement always looks important.
 *
 * <p>Sending a hundred thousand emails is the most expensive and least reversible thing this service
 * can be asked to do. It should take a reviewed configuration change, not a boolean.
 *
 * @param category     names a configured category, which decides the class and the channels
 * @param visibleFrom  defaults to now, so an immediate announcement omits it
 * @param visibleUntil mandatory - an announcement with no end is a banner nobody removes, and
 *                     retention is measured from here
 */
@Schema(name = "PublishAnnouncementRequest",
        description = "Publish one announcement. The category decides its class and channels.")
public record PublishAnnouncementRequest(

        @NotBlank(message = "{notification.validation.announcement.category.required}")
        @Size(max = 255)
        String category,

        @NotNull(message = "{notification.validation.announcement.audience.required}")
        AudienceTypeDto audienceType,

        @Schema(description = "The role code for a ROLE audience; omit for EVERYONE")
        @Size(max = 128)
        String audienceValue,

        @NotBlank(message = "{notification.validation.template-key.required}")
        @Size(max = 128)
        // Same path-safe alphabet as a notification's template key, and for the same reason: the key
        // becomes a directory name in the template tree, so one containing "../" would resolve
        // outside it.
        @Pattern(regexp = "[a-z0-9][a-z0-9._-]*",
                message = "{notification.validation.template-key.pattern}")
        String templateKey,

        Map<String, Object> variables,

        Instant visibleFrom,

        @NotNull(message = "{notification.validation.announcement.visible-until.required}")
        Instant visibleUntil) {
}
