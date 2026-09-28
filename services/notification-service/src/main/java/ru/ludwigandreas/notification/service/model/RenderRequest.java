package ru.ludwigandreas.notification.service.model;

import java.util.Locale;
import java.util.Map;

/**
 * A render with no send behind it, for the template preview endpoint.
 *
 * <p>The endpoint exists because the alternative way to check a template change is to send a real
 * notification to a real person, and that is how a typo reaches a customer. It renders through
 * exactly the same resolver and the same strictness as a live send, so a preview that succeeds
 * guarantees the send would.
 */
public record RenderRequest(
        String templateKey,
        ChannelType channel,
        Locale locale,
        Map<String, Object> variables) {

    public RenderRequest {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
