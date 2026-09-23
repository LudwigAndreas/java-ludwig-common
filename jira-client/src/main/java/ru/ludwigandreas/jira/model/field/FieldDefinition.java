package ru.ludwigandreas.jira.model.field;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One entry of {@code GET /rest/api/2/field}: everything Jira knows about a field, on every project at
 * once.
 *
 * <p>This is the input to {@link ru.ludwigandreas.jira.field.CustomFieldRegistry}, which is how a caller
 * stops writing {@code "customfield_10234"} in application code. Two properties matter for that:
 * {@code id} is the JSON key the REST API uses, and {@code clauseNames} are the identifiers JQL accepts -
 * and they are not the same thing. A field named "Story Points" has id {@code customfield_10004} and clause
 * names {@code ["cf[10004]", "Story Points"]}; using the id in JQL does not work and using a clause name in
 * a field payload does not work either.
 *
 * <p>{@code name} is not unique. Two custom fields may share a display name, and a registry built by name
 * has to decide what to do about that - see the ambiguity handling on the registry.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldDefinition(String id,
                              String key,
                              String name,
                              Boolean custom,
                              Boolean orderable,
                              Boolean navigable,
                              Boolean searchable,
                              List<String> clauseNames,
                              FieldSchema schema) {

    /** Normalizes {@code clauseNames} to an immutable empty list rather than {@code null}. */
    public FieldDefinition {
        clauseNames = clauseNames == null ? List.of() : List.copyOf(clauseNames);
    }

    /** Whether this is a custom field, tolerating an absent flag. */
    public boolean isCustom() {
        return Boolean.TRUE.equals(custom);
    }
}
