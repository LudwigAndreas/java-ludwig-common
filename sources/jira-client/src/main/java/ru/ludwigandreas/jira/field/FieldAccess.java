package ru.ludwigandreas.jira.field;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import ru.ludwigandreas.jira.json.JiraJson;
import ru.ludwigandreas.jira.model.field.CustomFieldOption;
import ru.ludwigandreas.jira.model.issue.Issue;
import ru.ludwigandreas.jira.model.issue.IssueFields;
import ru.ludwigandreas.jira.model.user.JiraUser;

/**
 * Decodes a custom field's raw JSON into a real type.
 *
 * <p>The counterpart to {@link ru.ludwigandreas.jira.model.issue.IssueFields#raw(String)}, which
 * deliberately stops at {@link JsonNode}. Decoding needs to know the field's shape, and the shape is not
 * inferable from the JSON: {@code {"value":"Blue","id":"10100"}} is a select option, and
 * {@code {"name":"jsmith"}} is a user, and a caller reading either as a map gets something that compiles
 * and is wrong.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#fieldAccess()}. The typed accessors cover the
 * shapes Jira's own custom field types produce - see {@link CustomFieldTypes} - and
 * {@link #read(IssueFields, CustomField)} covers everything else, including a marketplace app's own shape,
 * by decoding into whatever type the caller declares.
 *
 * <p>Every method answers {@link Optional#empty()} for a field that is absent, unset, or explicitly null.
 * Those three are not distinguished, because Jira does not distinguish them in any way a caller can act on:
 * a field left off the {@code fields} parameter and a field with no value both arrive as nothing.
 */
public final class FieldAccess {

    private final JiraJson json;
    private final CustomFieldRegistry registry;

    public FieldAccess(JiraJson json, CustomFieldRegistry registry) {
        this.json = json;
        this.registry = registry;
    }

    /** The registry this instance resolves display names through, or {@code null} when none was supplied. */
    public CustomFieldRegistry registry() {
        return registry;
    }

    /** Reads a typed custom field off an issue. */
    public <T> Optional<T> read(Issue issue, CustomField<T> field) {
        return read(issue.fieldsOrEmpty(), field);
    }

    /** Reads a typed custom field off a fields object. */
    public <T> Optional<T> read(IssueFields fields, CustomField<T> field) {
        return fields.raw(field.id()).map(node -> json.convert(node, field.type()));
    }

    /**
     * Reads a field by display name, resolving the id through the registry.
     *
     * <p>Convenient, and one map lookup slower than using a {@link CustomField} constant. Resolve the name
     * once at startup where the call is on a hot path.
     *
     * @param fields the issue's fields
     * @param displayName the field's display name
     * @param type the type to decode to
     * @param <T> the value type
     * @return the decoded value, or empty when the field has none
     */
    public <T> Optional<T> readByName(IssueFields fields, String displayName, Class<T> type) {
        requireRegistry();
        return read(fields, registry.field(displayName, type));
    }

    /** Reads a text, URL or read-only-text custom field. */
    public Optional<String> readText(IssueFields fields, String fieldId) {
        return fields.raw(fieldId).filter(JsonNode::isTextual).map(JsonNode::asText);
    }

    /** Reads a number custom field. */
    public Optional<Double> readNumber(IssueFields fields, String fieldId) {
        return fields.raw(fieldId).filter(JsonNode::isNumber).map(JsonNode::asDouble);
    }

    /** Reads a date-picker custom field. */
    public Optional<LocalDate> readDate(IssueFields fields, String fieldId) {
        return readText(fields, fieldId).map(LocalDate::parse);
    }

    /** Reads a date-time custom field, in Jira's timestamp dialect. */
    public Optional<OffsetDateTime> readDateTime(IssueFields fields, String fieldId) {
        return fields.raw(fieldId).map(node -> json.convert(node,
                json.objectMapper().getTypeFactory().constructType(OffsetDateTime.class)));
    }

    /** Reads a single-select, radio-button or cascading-select custom field. */
    public Optional<CustomFieldOption> readOption(IssueFields fields, String fieldId) {
        return fields.raw(fieldId).map(node -> json.convert(node,
                json.objectMapper().getTypeFactory().constructType(CustomFieldOption.class)));
    }

    /** Reads a multi-select or checkbox custom field. */
    public List<CustomFieldOption> readOptions(IssueFields fields, String fieldId) {
        return readList(fields, fieldId, new TypeReference<List<CustomFieldOption>>() { });
    }

    /** Reads a single user-picker custom field. */
    public Optional<JiraUser> readUser(IssueFields fields, String fieldId) {
        return fields.raw(fieldId).map(node -> json.convert(node,
                json.objectMapper().getTypeFactory().constructType(JiraUser.class)));
    }

    /** Reads a multi user-picker custom field. */
    public List<JiraUser> readUsers(IssueFields fields, String fieldId) {
        return readList(fields, fieldId, new TypeReference<List<JiraUser>>() { });
    }

    /**
     * Reads a labels custom field.
     *
     * <p>Note the shape: labels are bare strings, not option objects, which is the one custom field type
     * whose multi-valued form is not a list of objects.
     *
     * @param fields the issue's fields
     * @param fieldId the field id
     * @return the labels, empty when the field has none
     */
    public List<String> readLabels(IssueFields fields, String fieldId) {
        return readList(fields, fieldId, new TypeReference<List<String>>() { });
    }

    /** Reads any array-valued field into a declared list type. */
    public <T> List<T> readList(IssueFields fields, String fieldId, TypeReference<List<T>> type) {
        return fields.raw(fieldId)
                .filter(JsonNode::isArray)
                .map(node -> json.<List<T>>convert(node, json.objectMapper().getTypeFactory().constructType(type)))
                .orElseGet(List::of);
    }

    private void requireRegistry() {
        if (registry == null) {
            throw new IllegalStateException("This FieldAccess was built without a CustomFieldRegistry, so it "
                    + "cannot resolve display names; build the client with one, or address fields by id");
        }
    }
}
