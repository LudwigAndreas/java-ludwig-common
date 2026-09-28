package ru.ludwigandreas.jira.model.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Restricts a comment or worklog to one group or one project role.
 *
 * <p>Getting this wrong leaks information, so it is worth being precise: {@code type} is {@code "group"} or
 * {@code "role"}, and {@code value} is the group name or the project role name - the <em>name</em>, not the
 * id, on Jira Server. Omitting the visibility entirely makes the comment readable by everyone who can
 * browse the issue.
 *
 * @param type {@code group} or {@code role}
 * @param value group name or project role name
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Visibility(String type, String value) {

    /** Restricts to members of a Jira group. */
    public static Visibility group(String groupName) {
        return new Visibility("group", groupName);
    }

    /** Restricts to holders of a project role. */
    public static Visibility role(String roleName) {
        return new Visibility("role", roleName);
    }
}
