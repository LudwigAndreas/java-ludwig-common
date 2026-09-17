package ru.ludwigandreas.notification.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a caller asks for.
 *
 * <p>Every constraint message is a bundle key rather than literal text, so a violation comes back in
 * the caller's language through the shared {@code web-core} problem pipeline.
 *
 * <p>Note what is <em>not</em> here: a subject, a body, an HTML fragment. A caller that could submit
 * rendered text would be deciding this service's wording, would bypass localization entirely, and
 * would turn every copy change into a coordinated release across every calling service. A template
 * key and a variable map is the contract.
 *
 * @param templateKey   template family, e.g. {@code password-reset}; the channel and the recipient's
 *                      locale complete the address
 * @param category      what the recipient's preferences are expressed against, e.g. {@code security}
 * @param scheduledAt   earliest send time; omit for as soon as possible. A time in the past is
 *                      accepted and means now, because a client clock that is a second fast should
 *                      not produce a rejection
 */
public record SendNotificationRequest(

        @NotBlank(message = "{notification.validation.template-key.required}")
        @Size(max = 128, message = "{notification.validation.template-key.size}")
        // Constrained to a path-safe alphabet because the key becomes a directory name in the
        // template tree: without this, a key containing "../" would resolve outside it.
        @Pattern(regexp = "[a-z0-9][a-z0-9._-]*",
                message = "{notification.validation.template-key.pattern}")
        String templateKey,

        @NotBlank(message = "{notification.validation.category.required}")
        @Size(max = 128, message = "{notification.validation.category.size}")
        String category,

        @NotNull(message = "{notification.validation.category-class.required}")
        CategoryClassDto categoryClass,

        @NotNull(message = "{notification.validation.priority.required}")
        PriorityDto priority,

        @NotEmpty(message = "{notification.validation.channels.required}")
        Set<ChannelTypeDto> channels,

        @NotEmpty(message = "{notification.validation.recipients.required}")
        @Size(max = 500, message = "{notification.validation.recipients.size}")
        List<@Valid RecipientDto> recipients,

        Map<String, Object> variables,

        Instant scheduledAt) {
}
