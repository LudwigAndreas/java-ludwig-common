package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import java.util.Optional;
import ru.ludwigandreas.jira.model.field.FieldMeta;

/**
 * {@code GET /rest/api/2/issue/{key}/editmeta}: exactly which fields the calling user may change on this
 * issue right now, given the edit screen, the workflow state and their permissions.
 *
 * <p>The right thing to consult before building an update. A field that is not in {@link #fields()} cannot
 * be set, and Jira rejects the whole update - not just that field - when one is included.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EditMeta(Map<String, FieldMeta> fields) {

    /** Normalizes {@code fields} to an immutable empty map rather than {@code null}. */
    public EditMeta {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    /** The metadata for one field id, empty when the field is not editable here. */
    public Optional<FieldMeta> field(String fieldId) {
        return Optional.ofNullable(fields.get(fieldId));
    }

    /** Whether the named field may be set on this issue by this user. */
    public boolean isEditable(String fieldId) {
        return fields.containsKey(fieldId);
    }
}
