package ru.ludwigandreas.jira.api;

import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.model.permission.Permissions;

/**
 * What the calling user is allowed to do.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#permissions()}.
 *
 * <p>Worth consulting before a write rather than after a 403, for two reasons. A 403 from Jira rarely says
 * which permission was missing, and Jira answers "you may not browse this" with a 404 rather than a 403 -
 * so a failed read is genuinely ambiguous, while {@code mypermissions} is not.
 */
public final class PermissionApi {

    private final JiraRestClient rest;

    public PermissionApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /** Every permission the instance defines, without saying whether the caller holds any of them. */
    public Permissions all() {
        return rest.get(ApiPaths.API_2 + "/permissions").operation("permission.list").as(Permissions.class);
    }

    /** Which permissions the calling user holds globally. */
    public Permissions mine() {
        return rest.get(ApiPaths.API_2 + "/mypermissions").operation("permission.mine").as(Permissions.class);
    }

    /** Which permissions the calling user holds in a project. */
    public Permissions mineInProject(String projectKey) {
        return rest.get(ApiPaths.API_2 + "/mypermissions")
                .operation("permission.mine.project")
                .query("projectKey", projectKey)
                .as(Permissions.class);
    }

    /** Which permissions the calling user holds on an issue, which is narrower than the project answer. */
    public Permissions mineOnIssue(String issueKey) {
        return rest.get(ApiPaths.API_2 + "/mypermissions")
                .operation("permission.mine.issue")
                .query("issueKey", issueKey)
                .as(Permissions.class);
    }
}
