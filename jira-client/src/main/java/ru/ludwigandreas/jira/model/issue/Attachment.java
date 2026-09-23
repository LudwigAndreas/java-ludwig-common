package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import ru.ludwigandreas.jira.model.user.JiraUser;

/**
 * An issue attachment's metadata.
 *
 * <p>{@code content} is the download URL and it requires the same authentication as the REST API itself -
 * it is not a public link. {@link ru.ludwigandreas.jira.api.AttachmentApi#download} follows it with the
 * client's credentials attached, which is the only reliable way to fetch it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Attachment(String self,
                         String id,
                         String filename,
                         JiraUser author,
                         OffsetDateTime created,
                         Long size,
                         String mimeType,
                         String content,
                         String thumbnail) {
}
