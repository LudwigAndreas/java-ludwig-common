package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An issue resolution.
 *
 * <p>An issue with a {@code null} resolution is open, whatever its status says. That is the distinction JQL
 * expresses as {@code resolution = EMPTY} and it is a more reliable "is this finished" test than the status
 * name, though {@link StatusCategory#isDone()} is more reliable still on a workflow that resolves without
 * setting a resolution.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Resolution(String self, String id, String name, String description) {

    /** A reference by id. */
    public static Resolution byId(String id) {
        return new Resolution(null, id, null, null);
    }

    /** A reference by name. */
    public static Resolution named(String name) {
        return new Resolution(null, null, name, null);
    }
}
