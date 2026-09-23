package ru.ludwigandreas.jira.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.ludwigandreas.jira.model.issue.RemoteIssueLink;

/**
 * The payload for creating or replacing a remote issue link.
 *
 * <p>Always set {@code globalId}. Jira treats a create carrying a {@code globalId} that already exists on
 * the issue as an update of that link, which makes the call idempotent - and an idempotent remote link is
 * the difference between a webhook consumer that fires twice leaving one link and one leaving two.
 *
 * @param globalId deduplication key, unique per issue
 * @param application the system the link points into
 * @param relationship how the link is described, for example {@code causes} or {@code mentioned in}
 * @param object the linked thing
 */
public record RemoteIssueLinkInput(@JsonProperty("globalId") String globalId,
                                   @JsonProperty("application") RemoteIssueLink.Application application,
                                   @JsonProperty("relationship") String relationship,
                                   @JsonProperty("object") RemoteIssueLink.RemoteObject object) {

    /** A link to a URL, deduplicated on that URL. */
    public static RemoteIssueLinkInput toUrl(String url, String title) {
        return new RemoteIssueLinkInput(
                url, null, null, new RemoteIssueLink.RemoteObject(url, title, null, null, null));
    }
}
