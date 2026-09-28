package ru.ludwigandreas.jira.model.project;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** A project role and, when read from the per-role endpoint, the actors currently holding it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectRole(String self, Long id, String name, String description, List<RoleActor> actors) {

    /**
     * One holder of a project role: either a user or a group.
     *
     * <p>{@code type} is {@code atlassian-user-role-actor} or {@code atlassian-group-role-actor}, and
     * {@code name} is the username or group name accordingly.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoleActor(Long id, String displayName, String type, String name, String avatarUrl) {
    }
}
