package ru.ludwigandreas.notification.service.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The template exists but could not be rendered with the variables supplied - almost always a
 * variable the template needs and the caller did not send.
 *
 * <p>This failing loudly is the whole design. FreeMarker can be configured to treat a missing
 * variable as an empty string, and then a password-reset email goes out reading "Hello , your code
 * is ." - delivered, accepted, useless, and invisible until somebody complains. A render that cannot
 * be completed is a rejected request, and the message names the expression that failed so the caller
 * can fix their payload without reading the template.
 */
public class TemplateRenderException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public TemplateRenderException(String templateName, String detail, Throwable cause) {
        super(ProblemStatus.UNPROCESSABLE, "error.notification.template.render-failed", cause,
                templateName, detail);
        withProperty("templateName", templateName);
    }
}
