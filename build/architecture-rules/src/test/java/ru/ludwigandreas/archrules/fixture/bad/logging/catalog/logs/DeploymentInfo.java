package ru.ludwigandreas.archrules.fixture.bad.logging.catalog.logs;

/** A second build-provenance type: nothing but the commit, the branch and the build time. */
public record DeploymentInfo(String commitId, String branch, String buildTimestamp, boolean dirty) {

    /** A constant is not state, and must not stop this from being recognised for what it is. */
    public static final DeploymentInfo NONE = new DeploymentInfo(null, null, null, false);
}
