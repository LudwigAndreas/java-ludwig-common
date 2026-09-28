package ru.ludwigandreas.jira.api;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.model.common.JiraGroup;
import ru.ludwigandreas.jira.model.user.JiraUser;
import ru.ludwigandreas.jira.page.Page;
import ru.ludwigandreas.jira.page.PageRequest;
import ru.ludwigandreas.jira.page.Pages;

/**
 * Groups and their membership.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#groups()}.
 *
 * <p>{@link #members} returns <em>direct</em> members only. Jira supports nested groups, and a user who
 * belongs through a nested group does not appear here even though {@code membersOf()} in JQL and every
 * permission check count them as a member. An access review built on this endpoint alone under-reports.
 */
public final class GroupApi {

    private static final String GROUP = ApiPaths.API_2 + "/group";

    private final JiraRestClient rest;

    public GroupApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /** Reads a group. */
    public JiraGroup get(String groupName) {
        return rest.get(GROUP).operation("group.get").query("groupname", groupName).as(JiraGroup.class);
    }

    /** Creates a group. */
    public JiraGroup create(String groupName) {
        return rest.post(GROUP).operation("group.create").body(Map.of("name", groupName)).as(JiraGroup.class);
    }

    /**
     * Deletes a group.
     *
     * @param groupName the group to delete
     * @param swapGroup a group to transfer the deleted group's permission and filter grants to, or
     *     {@code null} to drop them - which silently removes access for everyone who had it through this
     *     group
     */
    public void delete(String groupName, String swapGroup) {
        rest.delete(GROUP)
                .operation("group.delete")
                .query("groupname", groupName)
                .query("swapGroup", swapGroup)
                .asVoid();
    }

    /** One page of a group's direct members. */
    public Page<JiraUser> members(String groupName, PageRequest window, boolean includeInactive) {
        return rest.get(GROUP + "/member")
                .operation("group.members")
                .query("groupname", groupName)
                .query("startAt", window.startAt())
                .query("maxResults", window.maxResults())
                .query("includeInactiveUsers", includeInactive)
                .as(new com.fasterxml.jackson.core.type.TypeReference<Page<JiraUser>>() { });
    }

    /** Streams every direct member of a group. */
    public Stream<JiraUser> membersAll(String groupName, boolean includeInactive) {
        return Pages.stream(window -> members(groupName, window, includeInactive), PageRequest.DEFAULT_PAGE_SIZE);
    }

    /** Adds a user to a group. */
    public void addUser(String groupName, String username) {
        rest.post(GROUP + "/user")
                .operation("group.user.add")
                .query("groupname", groupName)
                .body(Map.of("name", username))
                .asVoid();
    }

    /** Removes a user from a group. */
    public void removeUser(String groupName, String username) {
        rest.delete(GROUP + "/user")
                .operation("group.user.remove")
                .query("groupname", groupName)
                .query("username", username)
                .asVoid();
    }

    /** Group-name suggestions for a picker. */
    public List<JiraGroup> pick(String query, int maxResults) {
        return rest.get(ApiPaths.API_2 + "/groups/picker")
                .operation("group.picker")
                .query("query", query)
                .query("maxResults", maxResults)
                .as(GroupPickerResult.class)
                .groups();
    }

    /** Jira's group-picker envelope. */
    private record GroupPickerResult(String header, Integer total, List<JiraGroup> groups) {

        GroupPickerResult {
            groups = groups == null ? List.of() : List.copyOf(groups);
        }
    }
}
