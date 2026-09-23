package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A link between two issues, as it appears in an issue's {@code issuelinks} field.
 *
 * <p>Exactly one of {@code inwardIssue} and {@code outwardIssue} is populated: the one that is <em>not</em>
 * the issue being read. Reading {@code issuelinks} on ABC-1 and finding an entry with an
 * {@code outwardIssue} of ABC-2 means "ABC-1 {@code type.outward} ABC-2".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IssueLink(String self, String id, IssueLinkType type, IssueRef inwardIssue, IssueRef outwardIssue) {

    /** The issue at the other end of this link, whichever direction it points. */
    public IssueRef otherEnd() {
        return inwardIssue != null ? inwardIssue : outwardIssue;
    }
}
