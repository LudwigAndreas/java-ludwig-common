package ru.ludwigandreas.jira.model.project;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * How many issues reference a version, which is what has to be checked before deleting or merging one.
 *
 * <p>The two counts answer different questions: {@code issuesFixedCount} is the {@code fixVersion} usage and
 * {@code issuesAffectedCount} the {@code affectedVersion} usage. Deleting a version with either non-zero
 * requires telling Jira what to move them to.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VersionIssueCounts(String self,
                                 Integer issuesFixedCount,
                                 Integer issuesAffectedCount,
                                 Integer issueCountWithCustomFieldsShowingVersion) {
}
