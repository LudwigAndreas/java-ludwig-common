package ru.ludwigandreas.jira.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

/**
 * The payload for creating or updating a project version.
 *
 * <p>Releasing a version is an update that sets {@code released} to true and normally sets
 * {@code releaseDate} at the same time; Jira does not fill the date in on its own, and a released version
 * with no release date sorts oddly in every report that uses it.
 *
 * @param name version name, unique within the project
 * @param description free text
 * @param project the project key, required on create
 * @param projectId the project id, an alternative to the key
 * @param startDate when work on the version starts
 * @param releaseDate when the version is or was released
 * @param archived whether the version is archived
 * @param released whether the version is released
 */
public record VersionInput(@JsonProperty("name") String name,
                           @JsonProperty("description") String description,
                           @JsonProperty("project") String project,
                           @JsonProperty("projectId") Long projectId,
                           @JsonProperty("startDate") LocalDate startDate,
                           @JsonProperty("releaseDate") LocalDate releaseDate,
                           @JsonProperty("archived") Boolean archived,
                           @JsonProperty("released") Boolean released) {

    /** A new, unreleased version in a project. */
    public static VersionInput create(String projectKey, String name) {
        return new VersionInput(name, null, projectKey, null, null, null, null, null);
    }

    /** An update that marks a version released on a given day. */
    public static VersionInput release(LocalDate releaseDate) {
        return new VersionInput(null, null, null, null, null, releaseDate, null, Boolean.TRUE);
    }
}
