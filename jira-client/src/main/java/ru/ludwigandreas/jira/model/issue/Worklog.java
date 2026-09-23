package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import ru.ludwigandreas.jira.model.common.Visibility;
import ru.ludwigandreas.jira.model.user.JiraUser;

/**
 * A worklog entry.
 *
 * <p>{@code started} is when the work happened and is what reports aggregate on; {@code created} is when the
 * entry was recorded. They are frequently different and confusing them produces time reports that are wrong
 * by days.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Worklog(String self,
                      String id,
                      String issueId,
                      JiraUser author,
                      JiraUser updateAuthor,
                      String comment,
                      OffsetDateTime created,
                      OffsetDateTime updated,
                      OffsetDateTime started,
                      String timeSpent,
                      Long timeSpentSeconds,
                      Visibility visibility) {
}
