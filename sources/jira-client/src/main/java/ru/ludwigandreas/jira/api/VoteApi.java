package ru.ludwigandreas.jira.api;

import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.http.JiraPaths;
import ru.ludwigandreas.jira.model.common.Votes;

/**
 * Votes on an issue.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#votes()}. A vote is always cast by the
 * authenticated user - there is no way to vote on someone else's behalf - and Jira refuses a vote on an
 * issue the caller reported.
 */
public final class VoteApi {

    private static final String ISSUE = ApiPaths.API_2 + "/issue";

    private final JiraRestClient rest;

    public VoteApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /** The vote state of an issue. */
    public Votes get(String issueKeyOrId) {
        return rest.get(JiraPaths.of(ISSUE, issueKeyOrId, "votes")).operation("vote.get").as(Votes.class);
    }

    /** Casts the calling user's vote. */
    public void add(String issueKeyOrId) {
        rest.post(JiraPaths.of(ISSUE, issueKeyOrId, "votes")).operation("vote.add").asVoid();
    }

    /** Withdraws the calling user's vote. */
    public void remove(String issueKeyOrId) {
        rest.delete(JiraPaths.of(ISSUE, issueKeyOrId, "votes")).operation("vote.remove").asVoid();
    }
}
