package ru.ludwigandreas.notification.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Render a template without sending it.
 *
 * <p>The endpoint exists because the alternative way to check a wording change is to send a real
 * notification to a real person, and that is how a typo reaches a customer. It goes through the same
 * resolver and the same strictness as a live send, so a preview that succeeds guarantees the send
 * would.
 */
public record RenderPreviewRequest(

        @NotBlank(message = "{notification.validation.template-key.required}")
        @Size(max = 128, message = "{notification.validation.template-key.size}")
        @Pattern(regexp = "[a-z0-9][a-z0-9._-]*",
                message = "{notification.validation.template-key.pattern}")
        String templateKey,

        @NotNull(message = "{notification.validation.channel.required}")
        ChannelTypeDto channel,

        @Size(max = 35, message = "{notification.validation.recipient.locale.size}")
        String locale,

        Map<String, Object> variables) {
}
