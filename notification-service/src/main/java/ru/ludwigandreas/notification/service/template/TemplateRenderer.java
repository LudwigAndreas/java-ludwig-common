package ru.ludwigandreas.notification.service.template;

import java.util.Map;
import ru.ludwigandreas.notification.service.exception.TemplateNotFoundException;
import ru.ludwigandreas.notification.service.exception.TemplateRenderException;

/**
 * Turns a template address and a variable map into text.
 *
 * <p>An interface rather than a class because the preview endpoint, the dispatcher and the digest
 * collapser all render, and none of them should know that FreeMarker is what does it - which is also
 * what lets the unit tests exercise the strictness rules without a Spring context.
 */
public interface TemplateRenderer {

    /**
     * @throws TemplateNotFoundException if no locale variant of the template exists
     * @throws TemplateRenderException   if the template exists but the variables cannot satisfy it
     */
    RenderedTemplate render(TemplateCoordinates coordinates, Map<String, Object> variables);
}
