package ru.ludwigandreas.archrules.fixture.bad.logging.catalog.logs;

/**
 * Not a second provenance type: a domain object that records a commit and a branch beside state of its
 * own, about a release rather than about the running process.
 */
public record ReleaseNote(String title, String body, String commitId, String branch) {
}
