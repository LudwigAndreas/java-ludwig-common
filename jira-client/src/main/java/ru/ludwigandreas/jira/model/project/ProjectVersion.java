package ru.ludwigandreas.jira.model.project;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;

/**
 * A project version, used for {@code fixVersions} and {@code versions} ("affects version") on an issue.
 *
 * <p>{@code overdue} and {@code userReleaseDate} are computed by Jira for display and are read-only; sending
 * them back on an update is harmless but meaningless. Dates are {@link LocalDate} because Jira stores a
 * version's release date as a day with no time and no zone.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectVersion(String self,
                             String id,
                             String name,
                             String description,
                             Boolean archived,
                             Boolean released,
                             LocalDate startDate,
                             LocalDate releaseDate,
                             Boolean overdue,
                             String userStartDate,
                             String userReleaseDate,
                             String project,
                             Long projectId) {

    /** A reference by id, for an issue payload. */
    public static ProjectVersion byId(String id) {
        return new ProjectVersion(null, id, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** A reference by name, which Jira resolves within the issue's project. */
    public static ProjectVersion named(String name) {
        return new ProjectVersion(null, null, name, null, null, null, null, null, null, null, null, null, null);
    }
}
