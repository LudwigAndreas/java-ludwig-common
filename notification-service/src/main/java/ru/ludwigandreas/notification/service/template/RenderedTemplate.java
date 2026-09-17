package ru.ludwigandreas.notification.service.template;

/**
 * The output of one render, before it becomes either a preview or a message on the wire.
 *
 * @param templateName    the body template actually used, fallback included - which is also the key
 *                        the revision trail is recorded under
 * @param templateSource  the raw text that produced this, carried so the revision can be recorded
 *                        without reading the file a second time and risking reading a different one
 * @param resolvedLocale  the locale actually used, which may be the fallback rather than the one
 *                        asked for - worth reporting, because a missing translation otherwise only
 *                        surfaces as a customer receiving the wrong language
 * @param templateVersion content hash of the body template, which is what ties a sent message to
 *                        exact text after the file has been edited twice
 */
public record RenderedTemplate(
        String templateName,
        String templateSource,
        String resolvedLocale,
        String templateVersion,
        String subject,
        String htmlBody,
        String textBody) {
}
