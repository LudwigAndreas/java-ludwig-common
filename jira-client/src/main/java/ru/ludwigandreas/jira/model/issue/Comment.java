package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import ru.ludwigandreas.jira.model.common.Visibility;
import ru.ludwigandreas.jira.model.user.JiraUser;

/**
 * An issue comment.
 *
 * <p>{@code body} is Jira wiki markup on Server 9.12, not Atlassian Document Format - ADF is a Jira Cloud
 * construct and does not appear here. {@code renderedBody} holds the HTML Jira produced from it, and is
 * populated only when the request asked for {@code expand=renderedBody}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Comment(String self,
                      String id,
                      JiraUser author,
                      String body,
                      String renderedBody,
                      JiraUser updateAuthor,
                      OffsetDateTime created,
                      OffsetDateTime updated,
                      Visibility visibility) {
}
