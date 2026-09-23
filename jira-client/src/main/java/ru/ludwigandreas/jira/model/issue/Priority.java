package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** An issue priority. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Priority(String self, String id, String name, String description, String iconUrl, String statusColor) {

    /** A reference by id, for a payload that sets the priority. */
    public static Priority byId(String id) {
        return new Priority(null, id, null, null, null, null);
    }

    /** A reference by name, which Jira resolves against the instance-wide priority scheme. */
    public static Priority named(String name) {
        return new Priority(null, null, name, null, null, null);
    }
}
