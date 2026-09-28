package ru.ludwigandreas.jira.model.filter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ru.ludwigandreas.jira.model.common.JiraGroup;
import ru.ludwigandreas.jira.model.user.JiraUser;

/** An email subscription to a filter: who receives it, on what schedule. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Subscription(Long id, JiraUser user, JiraGroup group) {
}
