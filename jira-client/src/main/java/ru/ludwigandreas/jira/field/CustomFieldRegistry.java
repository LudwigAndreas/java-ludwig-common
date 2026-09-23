package ru.ludwigandreas.jira.field;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.type.TypeFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import ru.ludwigandreas.jira.api.FieldApi;
import ru.ludwigandreas.jira.error.JiraException;
import ru.ludwigandreas.jira.model.field.FieldDefinition;
import ru.ludwigandreas.jira.model.field.FieldSchema;

/**
 * Resolves a custom field's display name to the id the REST API needs, once, so application code never
 * carries a {@code customfield_NNNNN} literal.
 *
 * <p>The problem it solves is that custom field ids are per-instance. A field called "Story Points" is
 * {@code customfield_10004} on one Jira and {@code customfield_11702} on another, so an id written into
 * source is a value that is correct in exactly one environment - and the failure in the others is silent,
 * because reading a field that does not exist returns no value rather than an error.
 *
 * <p>The intended shape is to build this once at startup and hold it:
 *
 * <pre>{@code
 * CustomFieldRegistry registry = CustomFieldRegistry.load(client.fields());
 * CustomField<Double> storyPoints = registry.field("Story Points", Double.class);
 * }</pre>
 *
 * <p><b>Names are not unique.</b> Jira permits two custom fields with the same display name, and they are
 * different fields with different ids on different screens. Rather than picking one silently, every
 * name-based lookup here throws on an ambiguity and names the candidate ids, so the problem surfaces at
 * startup rather than as an integration that reads the wrong field for six months.
 *
 * <p>Held in memory and never refreshed. A field added to Jira after this was built will not be found;
 * rebuild it, or restart, when the instance's field set changes. That is a deliberate trade against a
 * cache that expires in the middle of a batch job and makes one run behave differently from the next.
 */
public final class CustomFieldRegistry {

    private final Map<String, FieldDefinition> byId;
    private final Map<String, List<FieldDefinition>> byLowercaseName;

    private CustomFieldRegistry(List<FieldDefinition> definitions) {
        Map<String, FieldDefinition> ids = new LinkedHashMap<>();
        Map<String, List<FieldDefinition>> names = new LinkedHashMap<>();
        for (FieldDefinition definition : definitions) {
            if (definition.id() != null) {
                ids.put(definition.id(), definition);
            }
            if (definition.name() != null) {
                names.computeIfAbsent(definition.name().toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                        .add(definition);
            }
        }
        this.byId = Map.copyOf(ids);
        Map<String, List<FieldDefinition>> copied = new LinkedHashMap<>();
        names.forEach((key, value) -> copied.put(key, List.copyOf(value)));
        this.byLowercaseName = Map.copyOf(copied);
    }

    /** Builds a registry from an already-fetched field list. */
    public static CustomFieldRegistry of(List<FieldDefinition> definitions) {
        return new CustomFieldRegistry(definitions);
    }

    /** Fetches the instance's field list and builds a registry from it. */
    public static CustomFieldRegistry load(FieldApi fields) {
        return new CustomFieldRegistry(fields.list());
    }

    /** Every field definition the registry was built from. */
    public List<FieldDefinition> definitions() {
        return List.copyOf(byId.values());
    }

    /** One definition by API id. */
    public Optional<FieldDefinition> byId(String fieldId) {
        return Optional.ofNullable(byId.get(fieldId));
    }

    /** Every definition carrying a display name, matched case-insensitively. */
    public List<FieldDefinition> allByName(String displayName) {
        return byLowercaseName.getOrDefault(displayName.toLowerCase(Locale.ROOT), List.of());
    }

    /**
     * The single definition carrying a display name.
     *
     * @param displayName the field's display name, matched case-insensitively
     * @return the definition, or empty when no field carries that name
     * @throws JiraException when more than one field carries it
     */
    public Optional<FieldDefinition> byName(String displayName) {
        List<FieldDefinition> matches = allByName(displayName);
        if (matches.size() > 1) {
            throw new JiraException("This Jira defines " + matches.size() + " fields named '" + displayName
                    + "' (" + matches.stream().map(FieldDefinition::id).toList()
                    + "); name it by id instead, because which one a name resolves to is not defined");
        }
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    /**
     * The API id of the field with this display name.
     *
     * @param displayName the field's display name
     * @return the id, for example {@code customfield_10004}
     * @throws JiraException when no field or more than one field carries that name
     */
    public String requireId(String displayName) {
        return byName(displayName)
                .map(FieldDefinition::id)
                .orElseThrow(() -> new JiraException("This Jira defines no field named '" + displayName
                        + "'; check the name, or that the integration account can see the field"));
    }

    /**
     * A typed handle on the field with this display name.
     *
     * @param displayName the field's display name
     * @param type the type its value decodes to
     * @param <T> the value type
     * @return the typed field handle
     * @throws JiraException when the name does not resolve to exactly one field
     */
    public <T> CustomField<T> field(String displayName, Class<T> type) {
        return CustomField.of(requireId(displayName), displayName,
                TypeFactory.defaultInstance().constructType(type));
    }

    /** A typed handle on a field of a generic type. */
    public <T> CustomField<T> field(String displayName, TypeReference<T> type) {
        return CustomField.of(requireId(displayName), displayName,
                TypeFactory.defaultInstance().constructType(type));
    }

    /** The schema of a field by id. */
    public Optional<FieldSchema> schemaOf(String fieldId) {
        return byId(fieldId).map(FieldDefinition::schema);
    }

    /**
     * The {@code cf[10004]} form for JQL, resolved from a display name.
     *
     * <p>Worth preferring over naming the field in JQL: the id survives a rename, and it is unambiguous
     * where two fields share a name - which is a JQL parse error rather than a wrong answer, but a parse
     * error in production all the same.
     *
     * @param displayName the field's display name
     * @return the JQL clause name, for example {@code cf[10004]}
     * @throws JiraException when the name does not resolve, or the field is not a custom field
     */
    public String jqlClauseName(String displayName) {
        FieldDefinition definition = byName(displayName)
                .orElseThrow(() -> new JiraException("This Jira defines no field named '" + displayName + "'"));
        FieldSchema schema = definition.schema();
        if (schema == null || schema.customId() == null) {
            throw new JiraException("Field '" + displayName + "' is not a custom field, so it has no cf[] form; "
                    + "use its name directly in JQL");
        }
        return "cf[" + schema.customId() + "]";
    }
}
