package ru.ludwigandreas.jira.model.filter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ru.ludwigandreas.jira.model.common.JiraGroup;
import ru.ludwigandreas.jira.model.project.ProjectRef;
import ru.ludwigandreas.jira.model.project.ProjectRole;

/**
 * Who a filter or dashboard is shared with.
 *
 * <p>{@code type} is {@code global}, {@code group}, {@code project}, {@code projectRole} or
 * {@code authenticated}, and the populated companion field depends on it. A filter created with no share
 * permissions is private to its owner, which is usually not what an integration that creates filters for a
 * team intends.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SharePermission(Long id, String type, ProjectRef project, ProjectRole role, JiraGroup group) {

    /** Shared with everyone, including anonymous users where anonymous access is enabled. */
    public static SharePermission global() {
        return new SharePermission(null, "global", null, null, null);
    }

    /** Shared with every logged-in user. */
    public static SharePermission authenticated() {
        return new SharePermission(null, "authenticated", null, null, null);
    }

    /** Shared with the members of a group. */
    public static SharePermission group(String groupName) {
        return new SharePermission(null, "group", null, null, JiraGroup.named(groupName));
    }

    /** Shared with everyone who can browse a project. */
    public static SharePermission project(String projectId) {
        return new SharePermission(null, "project", ProjectRef.byId(projectId), null, null);
    }
}
