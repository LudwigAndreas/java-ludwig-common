package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An issue security level, the {@code security} field.
 *
 * <p>Setting it restricts who can see the issue at all. Clearing it - by sending {@code null} - makes the
 * issue visible to everyone who can browse the project, which is a disclosure, so this client never clears
 * it implicitly: an update that does not mention {@code security} leaves it alone.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IssueSecurityLevel(String self, String id, String name, String description) {

    /** A reference by id. */
    public static IssueSecurityLevel byId(String id) {
        return new IssueSecurityLevel(null, id, null, null);
    }
}
