package ru.ludwigandreas.jira.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.http.JiraPaths;
import ru.ludwigandreas.jira.model.issue.IssueType;
import ru.ludwigandreas.jira.model.project.Project;
import ru.ludwigandreas.jira.model.project.ProjectComponent;
import ru.ludwigandreas.jira.model.project.ProjectRole;
import ru.ludwigandreas.jira.model.project.ProjectVersion;

/**
 * Projects, and the components, versions, roles and issue types that hang off them.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#projects()}.
 */
public final class ProjectApi {

    private static final String PROJECT = ApiPaths.API_2 + "/project";

    private final JiraRestClient rest;

    public ProjectApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /**
     * Every project the calling user can browse.
     *
     * <p>Not paginated on Jira Server: this returns the whole list in one response, which on a large
     * instance is a slow call worth caching rather than repeating per request.
     *
     * @param expand expansions such as {@code description}, {@code lead}, {@code issueTypes}, {@code url}
     * @return the visible projects
     */
    public List<Project> list(Collection<String> expand) {
        return rest.get(PROJECT)
                .operation("project.list")
                .queryJoined("expand", expand)
                .as(new com.fasterxml.jackson.core.type.TypeReference<List<Project>>() { });
    }

    /** Every project the calling user can browse, unexpanded. */
    public List<Project> list() {
        return list(List.of());
    }

    /** Reads one project. */
    public Project get(String projectKeyOrId, Collection<String> expand) {
        return rest.get(JiraPaths.of(PROJECT, projectKeyOrId))
                .operation("project.get")
                .queryJoined("expand", expand)
                .as(Project.class);
    }

    /** Reads one project, unexpanded. */
    public Project get(String projectKeyOrId) {
        return get(projectKeyOrId, List.of());
    }

    /** Reads one project, answering empty rather than throwing when it does not exist or is not visible. */
    public Optional<Project> find(String projectKeyOrId) {
        return rest.get(JiraPaths.of(PROJECT, projectKeyOrId)).operation("project.get").asOptional(Project.class);
    }

    /** The components of a project. */
    public List<ProjectComponent> components(String projectKeyOrId) {
        return rest.get(JiraPaths.of(PROJECT, projectKeyOrId, "components"))
                .operation("project.components")
                .as(new com.fasterxml.jackson.core.type.TypeReference<List<ProjectComponent>>() { });
    }

    /** The versions of a project, in the order the project defines. */
    public List<ProjectVersion> versions(String projectKeyOrId) {
        return rest.get(JiraPaths.of(PROJECT, projectKeyOrId, "versions"))
                .operation("project.versions")
                .as(new com.fasterxml.jackson.core.type.TypeReference<List<ProjectVersion>>() { });
    }

    /**
     * The statuses available in a project, grouped by issue type.
     *
     * <p>The cheapest way to learn a project's workflow shape without reading workflow schemes: each issue
     * type carries the statuses its workflow can reach.
     *
     * @param projectKeyOrId the project
     * @return the issue types, each with its reachable statuses
     */
    public List<IssueTypeStatuses> statuses(String projectKeyOrId) {
        return rest.get(JiraPaths.of(PROJECT, projectKeyOrId, "statuses"))
                .operation("project.statuses")
                .as(new com.fasterxml.jackson.core.type.TypeReference<List<IssueTypeStatuses>>() { });
    }

    /** The project's roles, as a map of role name to the URL of that role's actors. */
    public Map<String, String> roles(String projectKeyOrId) {
        return rest.get(JiraPaths.of(PROJECT, projectKeyOrId, "role"))
                .operation("project.roles")
                .as(new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() { });
    }

    /** One project role, with the users and groups currently holding it. */
    public ProjectRole role(String projectKeyOrId, long roleId) {
        return rest.get(JiraPaths.of(PROJECT, projectKeyOrId, "role", roleId))
                .operation("project.role.get")
                .as(ProjectRole.class);
    }

    /**
     * Adds actors to a project role.
     *
     * @param projectKeyOrId the project
     * @param roleId the role
     * @param usernames users to add, or an empty list
     * @param groupNames groups to add, or an empty list
     * @return the role as it now stands
     */
    public ProjectRole addRoleActors(String projectKeyOrId, long roleId,
                                     List<String> usernames, List<String> groupNames) {
        return rest.post(JiraPaths.of(PROJECT, projectKeyOrId, "role", roleId))
                .operation("project.role.add")
                .body(Map.of("user", usernames, "group", groupNames))
                .as(ProjectRole.class);
    }

    /** One issue type together with the statuses its workflow can reach in a given project. */
    public record IssueTypeStatuses(String self,
                                    String id,
                                    String name,
                                    Boolean subtask,
                                    List<ru.ludwigandreas.jira.model.issue.Status> statuses) {

        /** Normalizes {@code statuses} to an immutable empty list rather than {@code null}. */
        public IssueTypeStatuses {
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
        }

        /** This entry as a plain issue type reference. */
        public IssueType asIssueType() {
            return new IssueType(self, id, name, null, null, subtask, null);
        }
    }
}
