package ru.ludwigandreas.jira.model.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A key/value property attached to an issue, project or comment.
 *
 * <p>Entity properties are the supported way for an integration to store its own state on a Jira object
 * without adding a custom field: they are arbitrary JSON, they are not indexed for JQL, and they are
 * invisible in the UI. The value stays a {@link JsonNode} because only the owner of the key knows its shape.
 *
 * @param key property key, unique per entity
 * @param value arbitrary JSON value
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EntityProperty(String key, JsonNode value) {
}
