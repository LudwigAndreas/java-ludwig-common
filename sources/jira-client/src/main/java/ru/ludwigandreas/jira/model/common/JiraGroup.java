package ru.ludwigandreas.jira.model.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A Jira group reference.
 *
 * <p>On Jira Server a group is identified by its {@code name}; the {@code groupId} that Jira Cloud uses does
 * not exist here, which is why every group-taking API in this client takes a name.
 *
 * @param name group name, the group's identity on Jira Server
 * @param self absolute URL of the group resource
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraGroup(String name, String self) {

    /** A reference by name, for use in a request payload. */
    public static JiraGroup named(String name) {
        return new JiraGroup(name, null);
    }
}
