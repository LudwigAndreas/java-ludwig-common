package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A lightweight reference to an issue: the shape Jira embeds inside links, subtask lists and parents.
 *
 * <p>{@code fields} carries the handful Jira chooses to embed - summary, status, priority, issue type - not
 * the full field set. Reading anything else about the referenced issue needs a separate fetch.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IssueRef(String self, String id, String key, IssueFields fields) {

    /** A reference by key, for a payload that names an issue. */
    public static IssueRef byKey(String key) {
        return new IssueRef(null, null, key, null);
    }

    /** A reference by numeric id. */
    public static IssueRef byId(String id) {
        return new IssueRef(null, id, null, null);
    }
}
