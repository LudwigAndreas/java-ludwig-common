package ru.ludwigandreas.jira.model.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code self} link every Jira resource carries, modelled where nothing else about the resource is
 * needed.
 *
 * @param self absolute URL of the resource on the instance that produced it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraLink(String self) {
}
