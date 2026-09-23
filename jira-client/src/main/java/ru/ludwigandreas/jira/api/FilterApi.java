package ru.ludwigandreas.jira.api;

import java.util.List;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.http.JiraPaths;
import ru.ludwigandreas.jira.model.filter.Filter;
import ru.ludwigandreas.jira.model.filter.SharePermission;
import ru.ludwigandreas.jira.request.FilterInput;

/**
 * Saved filters.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#filters()}. Running a filter means taking its
 * {@link Filter#jql()} and passing it to {@link SearchApi} - Jira has no "execute filter" endpoint.
 */
public final class FilterApi {

    private static final String FILTER = ApiPaths.API_2 + "/filter";

    private final JiraRestClient rest;

    public FilterApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /** Reads one filter. */
    public Filter get(String filterId) {
        return rest.get(JiraPaths.of(FILTER, filterId)).operation("filter.get").as(Filter.class);
    }

    /** The calling user's favourite filters. */
    public List<Filter> favourites() {
        return rest.get(FILTER + "/favourite")
                .operation("filter.favourites")
                .as(new com.fasterxml.jackson.core.type.TypeReference<List<Filter>>() { });
    }

    /** Creates a filter. */
    public Filter create(FilterInput input) {
        return rest.post(FILTER).operation("filter.create").body(input).as(Filter.class);
    }

    /** Updates a filter. */
    public Filter update(String filterId, FilterInput input) {
        return rest.put(JiraPaths.of(FILTER, filterId)).operation("filter.update").body(input).as(Filter.class);
    }

    /** Deletes a filter. */
    public void delete(String filterId) {
        rest.delete(JiraPaths.of(FILTER, filterId)).operation("filter.delete").asVoid();
    }

    /** The filter's share permissions. */
    public List<SharePermission> sharePermissions(String filterId) {
        return rest.get(JiraPaths.of(FILTER, filterId, "permission"))
                .operation("filter.permissions")
                .as(new com.fasterxml.jackson.core.type.TypeReference<List<SharePermission>>() { });
    }

    /** Adds a share permission to a filter. */
    public List<SharePermission> addSharePermission(String filterId, SharePermission permission) {
        return rest.post(JiraPaths.of(FILTER, filterId, "permission"))
                .operation("filter.permission.add")
                .body(permission)
                .as(new com.fasterxml.jackson.core.type.TypeReference<List<SharePermission>>() { });
    }

    /** Removes a share permission from a filter. */
    public void removeSharePermission(String filterId, long permissionId) {
        rest.delete(JiraPaths.of(FILTER, filterId, "permission", permissionId))
                .operation("filter.permission.remove")
                .asVoid();
    }
}
