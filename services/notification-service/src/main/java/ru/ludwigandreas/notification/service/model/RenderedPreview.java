package ru.ludwigandreas.notification.service.model;

/**
 * The result of a preview render.
 *
 * @param resolvedLocale the locale actually used, which may be the fallback rather than the one
 *                       asked for - the single most useful thing this endpoint reports, because a
 *                       missing translation otherwise only shows up as a customer receiving English
 * @param templateVersion content hash of the source that produced this, so a preview can be quoted
 */
public record RenderedPreview(
        String templateKey,
        ChannelType channel,
        String resolvedLocale,
        String templateVersion,
        String subject,
        String htmlBody,
        String textBody) {
}
