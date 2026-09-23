package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What Jira returns from a create: identity only, not the issue.
 *
 * <p>The created issue's field values are deliberately not echoed back, because post-functions, workflow
 * listeners and field defaults may have changed them. Read the issue back if the stored values matter -
 * assuming they equal what was sent is a common source of drift.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreatedIssue(String id, String key, String self) {
}
