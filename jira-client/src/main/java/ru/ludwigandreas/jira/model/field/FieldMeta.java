package ru.ludwigandreas.jira.model.field;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * What a specific screen allows for a specific field, from {@code /issue/{key}/editmeta},
 * {@code /issue/createmeta} or a transition expansion.
 *
 * <p>This is the difference between "the field exists" and "you may set it here". A field absent from the
 * edit metadata cannot be updated on that issue at all - it is not on the screen - and sending it anyway
 * gets a 400 naming the field, which is the single most common cause of a failing Jira write.
 *
 * <p>{@code operations} lists what may be done to the field on this screen ({@code set}, {@code add},
 * {@code remove}, {@code edit}), which is the mechanism behind
 * {@link ru.ludwigandreas.jira.request.IssueUpdate}'s add/remove operations. {@code allowedValues} enumerates
 * the legal options for a select, version or component field, and is what a UI should populate a dropdown
 * from rather than guessing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldMeta(Boolean required,
                        FieldSchema schema,
                        String name,
                        String fieldId,
                        String autoCompleteUrl,
                        Boolean hasDefaultValue,
                        List<String> operations,
                        List<JsonNode> allowedValues,
                        JsonNode defaultValue) {

    /** Normalizes the two collections to immutable empty lists rather than {@code null}. */
    public FieldMeta {
        operations = operations == null ? List.of() : List.copyOf(operations);
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }

    /** Whether the field must be supplied on this screen. */
    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }

    /** Whether the named operation is permitted for this field on this screen. */
    public boolean supports(String operation) {
        return operations.contains(operation);
    }
}
