package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Instance-wide attachment settings: whether attachments are enabled at all, and the per-file size ceiling.
 *
 * <p>Worth reading once at startup. Uploading past {@code uploadLimit} fails with a 413 that Jira does not
 * describe usefully, and checking the limit locally turns that into a clear error before the bytes are sent.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AttachmentMeta(Boolean enabled, Long uploadLimit) {
}
