package ru.ludwigandreas.jira.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The payload for creating or updating a project component.
 *
 * <p>{@code assigneeType} takes {@code PROJECT_DEFAULT}, {@code COMPONENT_LEAD}, {@code PROJECT_LEAD} or
 * {@code UNASSIGNED}; setting {@code COMPONENT_LEAD} without a {@code leadUserName} leaves the component
 * with a rule it cannot satisfy, which Jira reports on the component as an invalid assignee type rather
 * than as an error on this call.
 *
 * @param name component name, unique within the project
 * @param description free text
 * @param project the project key, required on create
 * @param leadUserName username of the component lead
 * @param assigneeType default-assignee rule
 */
public record ComponentInput(@JsonProperty("name") String name,
                             @JsonProperty("description") String description,
                             @JsonProperty("project") String project,
                             @JsonProperty("leadUserName") String leadUserName,
                             @JsonProperty("assigneeType") String assigneeType) {

    /** A new component in a project. */
    public static ComponentInput create(String projectKey, String name) {
        return new ComponentInput(name, null, projectKey, null, null);
    }
}
