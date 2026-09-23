package ru.ludwigandreas.jira.api;

import java.util.List;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.model.server.JiraConfiguration;
import ru.ludwigandreas.jira.model.server.ServerInfo;
import ru.ludwigandreas.jira.model.user.ApplicationRole;

/**
 * Instance-level information: version, enabled features, licensed applications.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#server()}. Reading {@link #info()} and
 * {@link #configuration()} once at startup and logging them turns a whole class of later confusion - a
 * worklog write failing because time tracking is off, a personal access token failing on a Jira older than
 * 8.14 - into one line in the startup log.
 */
public final class ServerApi {

    private final JiraRestClient rest;

    public ServerApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /**
     * Which Jira this is.
     *
     * <p>The one endpoint that answers without authentication on most instances, which makes it the right
     * probe for "is the base URL correct" as distinct from "are the credentials correct".
     *
     * @return the instance's version and build
     */
    public ServerInfo info() {
        return rest.get(ApiPaths.API_2 + "/serverInfo").operation("server.info").as(ServerInfo.class);
    }

    /** Which optional features the instance has switched on. */
    public JiraConfiguration configuration() {
        return rest.get(ApiPaths.API_2 + "/configuration")
                .operation("server.configuration")
                .as(JiraConfiguration.class);
    }

    /** The application roles - licence buckets - defined on the instance. */
    public List<ApplicationRole> applicationRoles() {
        return rest.get(ApiPaths.API_2 + "/applicationrole")
                .operation("server.applicationroles")
                .as(new com.fasterxml.jackson.core.type.TypeReference<List<ApplicationRole>>() { });
    }
}
