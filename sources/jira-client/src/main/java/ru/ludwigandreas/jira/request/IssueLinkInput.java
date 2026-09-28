package ru.ludwigandreas.jira.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.ludwigandreas.jira.model.issue.IssueLinkType;
import ru.ludwigandreas.jira.model.issue.IssueRef;

/**
 * The payload for creating a link between two issues.
 *
 * <p>Direction is carried by which key goes in which slot, and it is easy to get backwards: with a link
 * type whose {@code outward} description is "blocks", {@code outwardIssue} is the issue that <em>is
 * blocked</em>. {@link #of(String, String, String)} names its parameters {@code inwardKey} and
 * {@code outwardKey} rather than "from" and "to" for that reason - there is no from and to.
 *
 * @param type the link type, by name
 * @param inwardIssue the issue at the inward end
 * @param outwardIssue the issue at the outward end
 * @param comment an optional comment added to the inward issue
 */
public record IssueLinkInput(@JsonProperty("type") IssueLinkType type,
                             @JsonProperty("inwardIssue") IssueRef inwardIssue,
                             @JsonProperty("outwardIssue") IssueRef outwardIssue,
                             @JsonProperty("comment") CommentInput comment) {

    /**
     * A link of the named type between two issues.
     *
     * @param linkTypeName the link type's name, for example {@code Blocks}
     * @param inwardKey key of the issue at the inward end of the relationship
     * @param outwardKey key of the issue at the outward end
     * @return the payload
     */
    public static IssueLinkInput of(String linkTypeName, String inwardKey, String outwardKey) {
        return new IssueLinkInput(
                IssueLinkType.named(linkTypeName), IssueRef.byKey(inwardKey), IssueRef.byKey(outwardKey), null);
    }
}
