package ru.ludwigandreas.jira.api;

import java.util.stream.Stream;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.http.JiraPaths;
import ru.ludwigandreas.jira.model.issue.Comment;
import ru.ludwigandreas.jira.page.Page;
import ru.ludwigandreas.jira.page.PageRequest;
import ru.ludwigandreas.jira.page.Pages;
import ru.ludwigandreas.jira.request.CommentInput;

/**
 * Comments on an issue.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#comments()}. Prefer these methods over the
 * {@code comment} collection embedded in an issue: that one is capped by Jira and silently truncated - see
 * {@link ru.ludwigandreas.jira.model.common.ItemList#isTruncated()}.
 */
public final class CommentApi {

    private static final String ISSUE = ApiPaths.API_2 + "/issue";

    private final JiraRestClient rest;

    public CommentApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /**
     * One page of an issue's comments.
     *
     * @param issueKeyOrId the issue
     * @param window which page to read
     * @param orderByCreatedDescending true for newest first
     * @return the page
     */
    public Page<Comment> list(String issueKeyOrId, PageRequest window, boolean orderByCreatedDescending) {
        return rest.get(JiraPaths.of(ISSUE, issueKeyOrId, "comment"))
                .operation("comment.list")
                .query("startAt", window.startAt())
                .query("maxResults", window.maxResults())
                .query("orderBy", orderByCreatedDescending ? "-created" : "created")
                .as(CommentPage.class)
                .toPage();
    }

    /** Streams every comment on an issue, oldest first. */
    public Stream<Comment> listAll(String issueKeyOrId) {
        return Pages.stream(window -> list(issueKeyOrId, window, false), PageRequest.DEFAULT_PAGE_SIZE);
    }

    /** Reads one comment. */
    public Comment get(String issueKeyOrId, String commentId) {
        return rest.get(JiraPaths.of(ISSUE, issueKeyOrId, "comment", commentId))
                .operation("comment.get")
                .as(Comment.class);
    }

    /** Adds a comment and returns it as stored, which is how to learn its id. */
    public Comment add(String issueKeyOrId, CommentInput input) {
        return rest.post(JiraPaths.of(ISSUE, issueKeyOrId, "comment"))
                .operation("comment.add")
                .body(input)
                .as(Comment.class);
    }

    /** Adds an unrestricted comment. */
    public Comment add(String issueKeyOrId, String body) {
        return add(issueKeyOrId, CommentInput.of(body));
    }

    /** Edits a comment. */
    public Comment update(String issueKeyOrId, String commentId, CommentInput input) {
        return rest.put(JiraPaths.of(ISSUE, issueKeyOrId, "comment", commentId))
                .operation("comment.update")
                .body(input)
                .as(Comment.class);
    }

    /** Deletes a comment. */
    public void delete(String issueKeyOrId, String commentId) {
        rest.delete(JiraPaths.of(ISSUE, issueKeyOrId, "comment", commentId))
                .operation("comment.delete")
                .asVoid();
    }

    /** Jira's comment page envelope, whose collection is called {@code comments} rather than {@code values}. */
    private record CommentPage(int startAt, int maxResults, int total, java.util.List<Comment> comments) {

        Page<Comment> toPage() {
            return new Page<>(startAt, maxResults, total, null, comments);
        }
    }
}
