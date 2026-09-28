package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** An issue status, together with the {@link StatusCategory} it belongs to. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Status(String self,
                     String id,
                     String name,
                     String description,
                     String iconUrl,
                     StatusCategory statusCategory) {

    /** A reference by id, for a payload that sets a status directly. */
    public static Status byId(String id) {
        return new Status(null, id, null, null, null, null);
    }

    /** Whether this status sits in the "Done" category, regardless of what it is called. */
    public boolean isDone() {
        return statusCategory != null && statusCategory.isDone();
    }
}
