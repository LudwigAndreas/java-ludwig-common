package ru.ludwigandreas.jira.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.ludwigandreas.jira.model.common.Visibility;

/**
 * The payload for adding or editing a comment.
 *
 * <p>An edit with a {@code null} visibility does not clear an existing restriction - it leaves it as it was.
 * Removing a restriction means sending a visibility the caller is allowed to set, or deleting and
 * re-adding the comment.
 *
 * @param body comment text, in Jira wiki markup
 * @param visibility restriction to a group or project role, or {@code null} for unrestricted
 */
public record CommentInput(@JsonProperty("body") String body,
                           @JsonProperty("visibility") Visibility visibility) {

    /** An unrestricted comment. */
    public static CommentInput of(String body) {
        return new CommentInput(body, null);
    }

    /** A comment visible only to members of a group. */
    public static CommentInput restrictedToGroup(String body, String groupName) {
        return new CommentInput(body, Visibility.group(groupName));
    }

    /** A comment visible only to holders of a project role. */
    public static CommentInput restrictedToRole(String body, String roleName) {
        return new CommentInput(body, Visibility.role(roleName));
    }
}
