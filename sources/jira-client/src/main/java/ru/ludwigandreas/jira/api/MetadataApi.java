package ru.ludwigandreas.jira.api;

import java.util.List;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.http.JiraPaths;
import ru.ludwigandreas.jira.model.issue.IssueType;
import ru.ludwigandreas.jira.model.issue.Priority;
import ru.ludwigandreas.jira.model.issue.Resolution;
import ru.ludwigandreas.jira.model.issue.Status;
import ru.ludwigandreas.jira.model.issue.StatusCategory;

/**
 * The instance-wide reference data an integration has to resolve names against: issue types, statuses,
 * priorities and resolutions.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#metadata()}. All of it changes rarely and is
 * worth caching for the lifetime of a process; none of it is paginated.
 */
public final class MetadataApi {

    private final JiraRestClient rest;

    public MetadataApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /** Every issue type on the instance, across all projects. */
    public List<IssueType> issueTypes() {
        return rest.get(ApiPaths.API_2 + "/issuetype")
                .operation("issuetype.list")
                .as(new com.fasterxml.jackson.core.type.TypeReference<List<IssueType>>() { });
    }

    /** One issue type by id. */
    public IssueType issueType(String issueTypeId) {
        return rest.get(JiraPaths.of(ApiPaths.API_2 + "/issuetype", issueTypeId))
                .operation("issuetype.get")
                .as(IssueType.class);
    }

    /** Every status on the instance. */
    public List<Status> statuses() {
        return rest.get(ApiPaths.API_2 + "/status")
                .operation("status.list")
                .as(new com.fasterxml.jackson.core.type.TypeReference<List<Status>>() { });
    }

    /** One status, by id or by name. */
    public Status status(String statusIdOrName) {
        return rest.get(JiraPaths.of(ApiPaths.API_2 + "/status", statusIdOrName))
                .operation("status.get")
                .as(Status.class);
    }

    /** The three status categories. */
    public List<StatusCategory> statusCategories() {
        return rest.get(ApiPaths.API_2 + "/statuscategory")
                .operation("statuscategory.list")
                .as(new com.fasterxml.jackson.core.type.TypeReference<List<StatusCategory>>() { });
    }

    /** Every priority, in the instance's configured order. */
    public List<Priority> priorities() {
        return rest.get(ApiPaths.API_2 + "/priority")
                .operation("priority.list")
                .as(new com.fasterxml.jackson.core.type.TypeReference<List<Priority>>() { });
    }

    /** Every resolution. */
    public List<Resolution> resolutions() {
        return rest.get(ApiPaths.API_2 + "/resolution")
                .operation("resolution.list")
                .as(new com.fasterxml.jackson.core.type.TypeReference<List<Resolution>>() { });
    }
}
