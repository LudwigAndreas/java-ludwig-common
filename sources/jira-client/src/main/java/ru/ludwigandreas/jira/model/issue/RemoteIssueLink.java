package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A remote link: a pointer from an issue to something outside Jira - a merge request, a Confluence page, a
 * record in another system.
 *
 * <p>{@code globalId} is the deduplication key. Creating a remote link with a {@code globalId} that already
 * exists on that issue updates it in place instead of adding a second one, which is what makes a webhook
 * consumer that fires twice harmless. Integrations that omit it accumulate duplicates.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteIssueLink(String self,
                              Long id,
                              String globalId,
                              Application application,
                              String relationship,
                              RemoteObject object) {

    /** The system the link points into, used by Jira to group links in the UI. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Application(String type, String name) {
    }

    /** The thing being linked to. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteObject(String url,
                               String title,
                               String summary,
                               Icon icon,
                               Status status) {
    }

    /** The small icon shown beside the link. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Icon(String url16x16, String title, String link) {
    }

    /** Optional resolution state, which renders the link struck through when {@code resolved} is true. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(Boolean resolved, Icon icon) {
    }
}
