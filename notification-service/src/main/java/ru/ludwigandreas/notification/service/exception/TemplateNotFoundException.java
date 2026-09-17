package ru.ludwigandreas.notification.service.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * No template exists for this key on this channel, in the requested locale or in the fallback.
 *
 * <p>422 rather than 404: the endpoint the caller addressed is perfectly real, it is the payload
 * that names something that is not there. Rendered at fan-out time, so a caller finds out
 * synchronously that the template they asked for does not exist rather than watching every delivery
 * quietly dead-letter.
 */
public class TemplateNotFoundException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public TemplateNotFoundException(String templateKey, String channel, String locale) {
        super(ProblemStatus.UNPROCESSABLE, "error.notification.template.not-found",
                templateKey, channel, locale);
        withProperty("templateKey", templateKey);
        withProperty("channel", channel);
        withProperty("locale", locale);
    }
}
