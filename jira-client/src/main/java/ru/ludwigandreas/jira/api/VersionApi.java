package ru.ludwigandreas.jira.api;

import java.util.Map;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.http.JiraPaths;
import ru.ludwigandreas.jira.model.project.ProjectVersion;
import ru.ludwigandreas.jira.model.project.VersionIssueCounts;
import ru.ludwigandreas.jira.request.VersionInput;

/**
 * Project versions.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#versions()}. Listing a project's versions
 * lives on {@link ProjectApi#versions(String)}, because that is where Jira puts it.
 */
public final class VersionApi {

    private static final String VERSION = ApiPaths.API_2 + "/version";

    private final JiraRestClient rest;

    public VersionApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /** Reads one version. */
    public ProjectVersion get(String versionId) {
        return rest.get(JiraPaths.of(VERSION, versionId)).operation("version.get").as(ProjectVersion.class);
    }

    /** Creates a version. */
    public ProjectVersion create(VersionInput input) {
        return rest.post(VERSION).operation("version.create").body(input).as(ProjectVersion.class);
    }

    /** Updates a version; fields left null are unchanged. */
    public ProjectVersion update(String versionId, VersionInput input) {
        return rest.put(JiraPaths.of(VERSION, versionId))
                .operation("version.update")
                .body(input)
                .as(ProjectVersion.class);
    }

    /**
     * Deletes a version, moving the issues that reference it.
     *
     * <p>Both replacement parameters are needed and they are separate: an issue can carry a version as a
     * fix version, as an affects version, or as both, and Jira will not infer one from the other. Passing
     * {@code null} for either means "just remove the reference", which silently loses information that no
     * one asked to lose - so consider whether a replacement version is the right answer first.
     *
     * @param versionId the version to delete
     * @param moveFixIssuesTo id of the version to move {@code fixVersion} references to, or {@code null}
     * @param moveAffectedIssuesTo id of the version to move {@code affectedVersion} references to, or {@code null}
     */
    public void delete(String versionId, String moveFixIssuesTo, String moveAffectedIssuesTo) {
        rest.delete(JiraPaths.of(VERSION, versionId))
                .operation("version.delete")
                .query("moveFixIssuesTo", moveFixIssuesTo)
                .query("moveAffectedIssuesTo", moveAffectedIssuesTo)
                .asVoid();
    }

    /** How many issues reference this version, split by which field references it. */
    public VersionIssueCounts issueCounts(String versionId) {
        return rest.get(JiraPaths.of(VERSION, versionId, "relatedIssueCounts"))
                .operation("version.issuecounts")
                .as(VersionIssueCounts.class);
    }

    /** How many issues with this fix version are still unresolved - the usual release-readiness check. */
    public int unresolvedIssueCount(String versionId) {
        Map<String, Object> counts = rest.get(JiraPaths.of(VERSION, versionId, "unresolvedIssueCount"))
                .operation("version.unresolvedcount")
                .as(new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        Object count = counts.get("issuesUnresolvedCount");
        return count instanceof Number number ? number.intValue() : 0;
    }

    /**
     * Moves a version within the project's ordering.
     *
     * @param versionId the version to move
     * @param position one of {@code earlier}, {@code later}, {@code First} or {@code Last}
     * @return the version as it now stands
     */
    public ProjectVersion move(String versionId, String position) {
        return rest.post(JiraPaths.of(VERSION, versionId, "move"))
                .operation("version.move")
                .body(Map.of("position", position))
                .as(ProjectVersion.class);
    }
}
