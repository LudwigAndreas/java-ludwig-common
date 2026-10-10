package ru.ludwigandreas.archrules.fixture.bad.logging.catalog.logs;

/** Not a second provenance type: a commit on its own identifies a change, not a build. */
public record CommitReference(String commitId) {
}
