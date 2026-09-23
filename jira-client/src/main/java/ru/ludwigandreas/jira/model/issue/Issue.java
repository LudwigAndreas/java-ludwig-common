package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.ludwigandreas.jira.model.field.FieldSchema;

/**
 * A Jira issue.
 *
 * <p>{@code key} ({@code ABC-123}) is what humans use and it <em>changes</em> when an issue is moved between
 * projects; {@code id} is the numeric identity and never changes. Anything this client stores about an
 * issue on the other side of a synchronisation should key on {@code id} and treat {@code key} as display
 * text, or a project move silently turns into a duplicate.
 *
 * <p>{@code names} and {@code schema} are populated only for {@code expand=names,schema} and are exactly
 * what a generic custom field mapping needs: {@code names} maps {@code customfield_10234} to its display
 * name, and {@code schema} maps it to its {@link FieldSchema}. When present, they let
 * {@link ru.ludwigandreas.jira.field.FieldAccess} decode custom fields without a separate call to
 * {@code /field}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Issue(String expand,
                    String id,
                    String self,
                    String key,
                    IssueFields fields,
                    Map<String, String> names,
                    Map<String, FieldSchema> schema,
                    Map<String, JsonNode> renderedFields,
                    Changelog changelog,
                    List<Transition> transitions,
                    List<Comment> comments) {

    /** Normalizes the expansion-dependent collections to immutable empty ones rather than {@code null}. */
    public Issue {
        names = names == null ? Map.of() : Map.copyOf(names);
        schema = schema == null ? Map.of() : Map.copyOf(schema);
        renderedFields = renderedFields == null ? Map.of() : Map.copyOf(renderedFields);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        comments = comments == null ? List.of() : List.copyOf(comments);
    }

    /** The fields object, never {@code null} even when the issue was fetched with {@code fields=-all}. */
    public IssueFields fieldsOrEmpty() {
        return fields == null ? new IssueFields() : fields;
    }

    /**
     * The API id of a field given its display name, from the {@code names} expansion.
     *
     * <p>Empty when the issue was not fetched with {@code expand=names}, or when no field carries that
     * name. Names are not unique; the first match in Jira's own order is returned.
     *
     * @param displayName the field's display name, matched case-insensitively
     * @return the field id, for example {@code customfield_10234}
     */
    public Optional<String> fieldIdForName(String displayName) {
        return names.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().equalsIgnoreCase(displayName))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** The HTML Jira rendered for a field, present only for {@code expand=renderedFields}. */
    public Optional<JsonNode> renderedField(String fieldId) {
        return Optional.ofNullable(renderedFields.get(fieldId));
    }
}
