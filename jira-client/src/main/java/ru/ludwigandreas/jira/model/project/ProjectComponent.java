package ru.ludwigandreas.jira.model.project;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ru.ludwigandreas.jira.model.user.JiraUser;

/**
 * A project component.
 *
 * <p>{@code assigneeType} drives Jira's default-assignee resolution and takes one of
 * {@code PROJECT_DEFAULT}, {@code COMPONENT_LEAD}, {@code PROJECT_LEAD} or {@code UNASSIGNED};
 * {@code realAssignee} is who Jira would actually pick, which differs from {@code assignee} when the
 * configured choice is not assignable - {@code isAssigneeTypeValid} is the flag that says so.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectComponent(String self,
                               String id,
                               String name,
                               String description,
                               JiraUser lead,
                               String leadUserName,
                               String assigneeType,
                               JiraUser assignee,
                               String realAssigneeType,
                               JiraUser realAssignee,
                               Boolean isAssigneeTypeValid,
                               String project,
                               Long projectId) {

    /** A reference by id, for an issue payload. */
    public static ProjectComponent byId(String id) {
        return new ProjectComponent(null, id, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** A reference by name, which Jira resolves within the issue's project. */
    public static ProjectComponent named(String name) {
        return new ProjectComponent(null, null, name, null, null, null, null, null, null, null, null, null, null);
    }
}
