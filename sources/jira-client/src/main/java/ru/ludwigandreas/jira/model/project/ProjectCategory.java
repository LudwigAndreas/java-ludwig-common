package ru.ludwigandreas.jira.model.project;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A project category, the grouping shown on the project list and usable in JQL as {@code category}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectCategory(String self, String id, String name, String description) {
}
