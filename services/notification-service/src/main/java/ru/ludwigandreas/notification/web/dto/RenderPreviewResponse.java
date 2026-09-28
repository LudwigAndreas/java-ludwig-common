package ru.ludwigandreas.notification.web.dto;

/**
 * The result of a preview render.
 *
 * @param resolvedLocale the locale actually used, which may be the fallback rather than the one
 *                       asked for. The single most useful thing this endpoint reports: a missing
 *                       translation otherwise shows up only as a customer receiving English
 */
public record RenderPreviewResponse(
        String templateKey,
        ChannelTypeDto channel,
        String resolvedLocale,
        String templateVersion,
        String subject,
        String htmlBody,
        String textBody) {
}
