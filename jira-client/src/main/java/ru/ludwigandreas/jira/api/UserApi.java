package ru.ludwigandreas.jira.api;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.model.user.JiraUser;
import ru.ludwigandreas.jira.model.user.UserPickerResult;
import ru.ludwigandreas.jira.page.Page;
import ru.ludwigandreas.jira.page.PageRequest;
import ru.ludwigandreas.jira.page.Pages;
import ru.ludwigandreas.jira.request.UserInput;

/**
 * Users.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#users()}.
 *
 * <p>Every method here identifies a user by <em>username</em>, because that is what Jira Server 9.12 uses.
 * {@code accountId} is a Jira Cloud concept and does not exist on this API; code ported from a Cloud client
 * will need that substitution everywhere.
 *
 * <p>The search endpoints share a trap worth stating once: they match against username, display name and
 * email, they exclude inactive accounts unless asked otherwise, and they return an <em>array</em> with no
 * total - so a search that returns exactly {@code maxResults} users may or may not have more. The paged
 * helpers here handle that by walking until a page comes back short.
 */
public final class UserApi {

    private static final String USER = ApiPaths.API_2 + "/user";

    private final JiraRestClient rest;

    public UserApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /** The account the client is authenticated as - the cheapest way to verify credentials work. */
    public JiraUser myself() {
        return rest.get(ApiPaths.API_2 + "/myself").operation("user.myself").as(JiraUser.class);
    }

    /** Reads one user by username. */
    public JiraUser get(String username) {
        return rest.get(USER).operation("user.get").query("username", username).as(JiraUser.class);
    }

    /** Reads one user by username, answering empty rather than throwing when there is no such account. */
    public Optional<JiraUser> find(String username) {
        return rest.get(USER).operation("user.get").query("username", username).asOptional(JiraUser.class);
    }

    /** Reads one user by their immutable user key, which survives a username change. */
    public JiraUser getByKey(String userKey) {
        return rest.get(USER).operation("user.get.bykey").query("key", userKey).as(JiraUser.class);
    }

    /**
     * Searches users.
     *
     * @param query matched against username, display name and email
     * @param window which page to read
     * @param includeInactive whether to include deactivated accounts
     * @return the matching users
     */
    public List<JiraUser> search(String query, PageRequest window, boolean includeInactive) {
        return rest.get(USER + "/search")
                .operation("user.search")
                .query("username", query)
                .query("startAt", window.startAt())
                .query("maxResults", window.maxResults())
                .query("includeInactive", includeInactive)
                .as(new TypeReference<List<JiraUser>>() { });
    }

    /** Streams every user matching a query, walking pages until one comes back short. */
    public Stream<JiraUser> searchAll(String query, boolean includeInactive) {
        return Pages.stream(window -> asPage(window, search(query, window, includeInactive)),
                PageRequest.DEFAULT_PAGE_SIZE);
    }

    /**
     * Users who may be assigned issues in a project.
     *
     * <p>Not the same as "users who can see the project": assignability requires the "Assignable User"
     * permission, so this is the list an assignee picker must be built from.
     *
     * @param projectKey the project
     * @param query matched against username, display name and email; may be empty for all
     * @param window which page to read
     * @return the assignable users
     */
    public List<JiraUser> assignableToProject(String projectKey, String query, PageRequest window) {
        return rest.get(USER + "/assignable/search")
                .operation("user.assignable.project")
                .query("project", projectKey)
                .query("username", query)
                .query("startAt", window.startAt())
                .query("maxResults", window.maxResults())
                .as(new TypeReference<List<JiraUser>>() { });
    }

    /** Users who may be assigned a specific issue, which narrows further than the project-wide list. */
    public List<JiraUser> assignableToIssue(String issueKey, String query, PageRequest window) {
        return rest.get(USER + "/assignable/search")
                .operation("user.assignable.issue")
                .query("issueKey", issueKey)
                .query("username", query)
                .query("startAt", window.startAt())
                .query("maxResults", window.maxResults())
                .as(new TypeReference<List<JiraUser>>() { });
    }

    /** Users who may browse a project's issues. */
    public List<JiraUser> withBrowsePermission(String projectKey, String query, PageRequest window) {
        return rest.get(USER + "/viewissue/search")
                .operation("user.viewissue")
                .query("projectKey", projectKey)
                .query("username", query)
                .query("startAt", window.startAt())
                .query("maxResults", window.maxResults())
                .as(new TypeReference<List<JiraUser>>() { });
    }

    /** Type-ahead suggestions for a user picker, with Jira's own highlighting. */
    public UserPickerResult picker(String query, int maxResults) {
        return rest.get(USER + "/picker")
                .operation("user.picker")
                .query("query", query)
                .query("maxResults", maxResults)
                .as(UserPickerResult.class);
    }

    /**
     * Creates a user in Jira's internal directory.
     *
     * <p>Fails on an instance whose users come from LDAP or Crowd, which is most enterprise deployments -
     * see {@link UserInput}.
     *
     * @param input the account to create
     * @return the created user
     */
    public JiraUser create(UserInput input) {
        return rest.post(USER).operation("user.create").body(input).as(JiraUser.class);
    }

    /** Updates a user. */
    public JiraUser update(String username, UserInput input) {
        return rest.put(USER).operation("user.update").query("username", username).body(input).as(JiraUser.class);
    }

    /**
     * Deletes a user.
     *
     * <p>Jira refuses when the account is referenced by issues, which is almost always. Deactivating -
     * {@code update} with {@code active: false} through the admin UI - is the operation that actually
     * applies to a departing employee.
     *
     * @param username the account to delete
     */
    public void delete(String username) {
        rest.delete(USER).operation("user.delete").query("username", username).asVoid();
    }

    private static Page<JiraUser> asPage(PageRequest window, List<JiraUser> users) {
        boolean last = users.size() < window.maxResults();
        return new Page<>(window.startAt(), window.maxResults(), null, last, users);
    }
}
