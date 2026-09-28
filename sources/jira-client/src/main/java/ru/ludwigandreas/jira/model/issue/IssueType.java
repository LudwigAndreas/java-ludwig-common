package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** An issue type. {@code subtask} is the flag that decides whether a {@code parent} is required. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IssueType(String self,
                        String id,
                        String name,
                        String description,
                        String iconUrl,
                        Boolean subtask,
                        Long avatarId) {

    /** A reference by id, which is the unambiguous form for a create payload. */
    public static IssueType byId(String id) {
        return new IssueType(null, id, null, null, null, null, null);
    }

    /**
     * A reference by name. Jira resolves it within the target project, so the same name can mean different
     * ids in different projects - prefer {@link #byId(String)} where the id is known.
     */
    public static IssueType named(String name) {
        return new IssueType(null, null, name, null, null, null, null);
    }
}
