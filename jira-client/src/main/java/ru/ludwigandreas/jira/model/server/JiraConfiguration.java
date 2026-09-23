package ru.ludwigandreas.jira.model.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code GET /rest/api/2/configuration}: which optional Jira features the instance has switched on.
 *
 * <p>Checking these beats discovering them through a 400. An instance with {@code timeTrackingEnabled}
 * false rejects every worklog write, and one with {@code subTasksEnabled} false rejects a create that names
 * a parent.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraConfiguration(Boolean votingEnabled,
                                Boolean watchingEnabled,
                                Boolean unassignedIssuesAllowed,
                                Boolean subTasksEnabled,
                                Boolean issueLinkingEnabled,
                                Boolean timeTrackingEnabled,
                                Boolean attachmentsEnabled,
                                TimeTrackingConfiguration timeTrackingConfiguration) {

    /** How the instance interprets a duration such as {@code "2d"}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimeTrackingConfiguration(Double workingHoursPerDay,
                                            Double workingDaysPerWeek,
                                            String timeFormat,
                                            String defaultUnit) {
    }
}
