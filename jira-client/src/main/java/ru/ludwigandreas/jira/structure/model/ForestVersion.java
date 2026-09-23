package ru.ludwigandreas.jira.structure.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The optimistic-concurrency token Structure attaches to a forest.
 *
 * <p>A forest update must carry the version the caller last observed. Structure rejects the update if the
 * forest has moved on since, which is what stops two clients reordering the same structure from silently
 * overwriting each other - the second one is told to re-read and reapply. That rejection surfaces here as
 * {@link ru.ludwigandreas.jira.error.JiraConflictException}.
 *
 * @param signature identifies the forest's identity, changing when the forest is rebuilt rather than edited
 * @param version increments on every change
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ForestVersion(long signature, long version) {

    /** The zero version, which Structure accepts as "I have not read this yet" on an item creation. */
    public static ForestVersion zero() {
        return new ForestVersion(0, 0);
    }
}
