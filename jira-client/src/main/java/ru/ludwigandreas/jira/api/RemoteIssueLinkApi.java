package ru.ludwigandreas.jira.api;

import java.util.List;
import java.util.Optional;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.http.JiraPaths;
import ru.ludwigandreas.jira.model.issue.RemoteIssueLink;
import ru.ludwigandreas.jira.request.RemoteIssueLinkInput;

/**
 * Remote links: pointers from an issue to something outside Jira.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#remoteLinks()}. Creating a link whose
 * {@code globalId} already exists on the issue replaces it, which makes {@link #create} idempotent as long
 * as the caller sets one.
 */
public final class RemoteIssueLinkApi {

    private static final String ISSUE = ApiPaths.API_2 + "/issue";

    private final JiraRestClient rest;

    public RemoteIssueLinkApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /** Every remote link on an issue. */
    public List<RemoteIssueLink> list(String issueKeyOrId) {
        return rest.get(JiraPaths.of(ISSUE, issueKeyOrId, "remotelink"))
                .operation("remotelink.list")
                .as(new com.fasterxml.jackson.core.type.TypeReference<List<RemoteIssueLink>>() { });
    }

    /** One remote link by its global id, empty when the issue carries no such link. */
    public Optional<RemoteIssueLink> findByGlobalId(String issueKeyOrId, String globalId) {
        return rest.get(JiraPaths.of(ISSUE, issueKeyOrId, "remotelink"))
                .operation("remotelink.get.byglobalid")
                .query("globalId", globalId)
                .asOptional(RemoteIssueLink.class);
    }

    /** Creates a remote link, or replaces the one carrying the same {@code globalId}. */
    public RemoteIssueLink create(String issueKeyOrId, RemoteIssueLinkInput input) {
        return rest.post(JiraPaths.of(ISSUE, issueKeyOrId, "remotelink"))
                .operation("remotelink.create")
                .body(input)
                .as(RemoteIssueLink.class);
    }

    /** Deletes a remote link by its Jira id. */
    public void delete(String issueKeyOrId, String linkId) {
        rest.delete(JiraPaths.of(ISSUE, issueKeyOrId, "remotelink", linkId))
                .operation("remotelink.delete")
                .asVoid();
    }

    /** Deletes a remote link by its global id, which is what an integration normally knows. */
    public void deleteByGlobalId(String issueKeyOrId, String globalId) {
        rest.delete(JiraPaths.of(ISSUE, issueKeyOrId, "remotelink"))
                .operation("remotelink.delete.byglobalid")
                .query("globalId", globalId)
                .asVoid();
    }
}
