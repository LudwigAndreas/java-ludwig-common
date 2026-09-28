package ru.ludwigandreas.jira.api;

import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.http.JiraPaths;
import ru.ludwigandreas.jira.model.common.Watches;

/**
 * Watchers on an issue.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#watchers()}.
 *
 * <p>Listing watchers needs the "Manage Watchers" permission. Without it Jira answers with the count and an
 * empty list rather than a 403, so an empty {@link Watches#watchers()} means either "nobody watches this"
 * or "you may not see who does" - compare it against {@link Watches#watchCount()} to tell them apart.
 */
public final class WatcherApi {

    private static final String ISSUE = ApiPaths.API_2 + "/issue";

    private final JiraRestClient rest;

    public WatcherApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /** The watch state of an issue, including the watcher list when permissions allow. */
    public Watches get(String issueKeyOrId) {
        return rest.get(JiraPaths.of(ISSUE, issueKeyOrId, "watchers"))
                .operation("watcher.list")
                .as(Watches.class);
    }

    /**
     * Adds a watcher.
     *
     * <p>Jira takes the username as a bare JSON string in the body, not as an object - which is why this is
     * the one write in the client that sends a quoted scalar.
     *
     * @param issueKeyOrId the issue
     * @param username the user to add
     */
    public void add(String issueKeyOrId, String username) {
        rest.post(JiraPaths.of(ISSUE, issueKeyOrId, "watchers"))
                .operation("watcher.add")
                .body(username)
                .asVoid();
    }

    /** Removes a watcher. */
    public void remove(String issueKeyOrId, String username) {
        rest.delete(JiraPaths.of(ISSUE, issueKeyOrId, "watchers"))
                .operation("watcher.remove")
                .query("username", username)
                .asVoid();
    }
}
