package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The three-bucket grouping every Jira status belongs to: To Do, In Progress, Done.
 *
 * <p>{@code key} is the stable identifier ({@code new}, {@code indeterminate}, {@code done}) and is the only
 * part of a status worth branching on in integration code. Status <em>names</em> are per-workflow and are
 * renamed by project admins without warning; the category key is fixed by Jira.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StatusCategory(String self, Long id, String key, String colorName, String name) {

    /** Category key of statuses that count as not started. */
    public static final String KEY_TODO = "new";

    /** Category key of statuses that count as in progress. */
    public static final String KEY_IN_PROGRESS = "indeterminate";

    /** Category key of statuses that count as finished. */
    public static final String KEY_DONE = "done";

    /** Whether this category is the "Done" bucket, which is the usual meaning of "is the issue finished". */
    public boolean isDone() {
        return KEY_DONE.equals(key);
    }
}
