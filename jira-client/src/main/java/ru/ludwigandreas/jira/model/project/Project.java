package ru.ludwigandreas.jira.model.project;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import ru.ludwigandreas.jira.model.issue.IssueType;
import ru.ludwigandreas.jira.model.user.JiraUser;

/**
 * A full Jira project.
 *
 * <p>Most of the collections here are populated only when asked for through {@code expand} - Jira's project
 * resource returns {@code description}, {@code lead}, {@code issueTypes}, {@code url} and
 * {@code projectKeys} on request, not by default - so an empty {@code issueTypes} usually means "not
 * expanded" rather than "none".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Project(String expand,
                      String self,
                      String id,
                      String key,
                      String name,
                      String description,
                      String url,
                      String email,
                      String assigneeType,
                      String projectTypeKey,
                      JiraUser lead,
                      Map<String, String> avatarUrls,
                      List<ProjectComponent> components,
                      List<ProjectVersion> versions,
                      List<IssueType> issueTypes,
                      List<String> projectKeys,
                      ProjectCategory projectCategory,
                      Boolean archived) {
}
