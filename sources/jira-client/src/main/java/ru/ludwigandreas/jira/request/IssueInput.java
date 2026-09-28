package ru.ludwigandreas.jira.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.NullNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.ludwigandreas.jira.model.common.TimeTracking;
import ru.ludwigandreas.jira.model.issue.IssueType;
import ru.ludwigandreas.jira.model.issue.Priority;
import ru.ludwigandreas.jira.model.issue.Resolution;
import ru.ludwigandreas.jira.model.issue.IssueSecurityLevel;
import ru.ludwigandreas.jira.model.project.ProjectComponent;
import ru.ludwigandreas.jira.model.project.ProjectRef;
import ru.ludwigandreas.jira.model.project.ProjectVersion;
import ru.ludwigandreas.jira.model.user.JiraUser;

/**
 * The payload of a create or an update: Jira's {@code fields} object, its {@code update} object, and any
 * entity properties to set in the same call.
 *
 * <p>One type for both operations because Jira uses one payload shape for both, and because the difference
 * that actually matters is not create-versus-update but {@code fields}-versus-{@code update}:
 *
 * <ul>
 *   <li><b>{@code fields} replaces.</b> {@code .labels("a", "b")} sets the label set to exactly those two,
 *       discarding whatever was there. This is what most callers mean on a create and almost never what
 *       they mean on an update of a multi-valued field.</li>
 *   <li><b>{@code update} mutates.</b> {@code .addLabel("a")} adds one label and leaves the rest alone -
 *       and, crucially, does not lose a label another process added between the read and the write. Every
 *       multi-valued field here has an add/remove pair for that reason.</li>
 * </ul>
 *
 * <p>A field that is not mentioned at all is left alone; a field cleared with {@link Builder#clear(String)}
 * is explicitly set to null. The two are different on the wire and the distinction is preserved here - an
 * absent key and a {@code null} value mean different things to Jira, which is why an unset optional is
 * never serialized as {@code null}.
 *
 * <p>Both sections can be used in one request. Jira applies {@code fields} first, then {@code update}.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class IssueInput {

    private final Map<String, Object> fields;
    private final Map<String, List<Map<String, Object>>> update;
    private final List<EntityPropertyInput> properties;

    private IssueInput(Builder builder) {
        // Insertion-ordered throughout: Map.copyOf returns a HashMap, and a payload whose field order
        // changes between JVM runs cannot be compared against a log line or a recorded fixture.
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(builder.fields));
        Map<String, List<Map<String, Object>>> copiedUpdate = new LinkedHashMap<>();
        builder.update.forEach((key, ops) -> copiedUpdate.put(key, List.copyOf(ops)));
        this.update = Collections.unmodifiableMap(copiedUpdate);
        this.properties = List.copyOf(builder.properties);
    }

    /** A new, empty payload builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** The {@code fields} section: values that replace whatever is stored. */
    @JsonProperty("fields")
    public Map<String, Object> fields() {
        return fields;
    }

    /** The {@code update} section: per-field operations that mutate what is stored. */
    @JsonProperty("update")
    public Map<String, List<Map<String, Object>>> update() {
        return update;
    }

    /** Entity properties to set in the same call. */
    @JsonProperty("properties")
    public List<EntityPropertyInput> properties() {
        return properties;
    }

    /** Whether this payload would change nothing, which Jira rejects with a 400. */
    public boolean isEmpty() {
        return fields.isEmpty() && update.isEmpty() && properties.isEmpty();
    }

    /** One entity property carried alongside an issue create or update. */
    public record EntityPropertyInput(@JsonProperty("key") String key, @JsonProperty("value") Object value) {
    }

    /**
     * Fluent builder for {@link IssueInput}.
     *
     * <p>Every system field has a typed method; everything else goes through {@link #field(String, Object)}
     * or, better, the {@code CustomField}-taking overload, which carries the field's value type in its
     * signature.
     */
    public static final class Builder {

        private final Map<String, Object> fields = new LinkedHashMap<>();
        private final Map<String, List<Map<String, Object>>> update = new LinkedHashMap<>();
        private final List<EntityPropertyInput> properties = new ArrayList<>();

        private Builder() {
        }

        /**
         * Sets the project by key. Required on a create, and forbidden on an update - Jira does not move an
         * issue between projects this way.
         */
        public Builder project(String projectKey) {
            return field("project", ProjectRef.byKey(projectKey));
        }

        /** Sets the project by numeric id. */
        public Builder projectId(String projectId) {
            return field("project", ProjectRef.byId(projectId));
        }

        /** Sets the issue type by name, resolved within the target project. */
        public Builder issueType(String name) {
            return field("issuetype", IssueType.named(name));
        }

        /** Sets the issue type by id, which is unambiguous across projects. */
        public Builder issueTypeId(String id) {
            return field("issuetype", IssueType.byId(id));
        }

        /** Sets the summary. */
        public Builder summary(String summary) {
            return field("summary", summary);
        }

        /** Sets the description, in Jira wiki markup. */
        public Builder description(String description) {
            return field("description", description);
        }

        /** Sets the environment field, in Jira wiki markup. */
        public Builder environment(String environment) {
            return field("environment", environment);
        }

        /**
         * Sets the assignee by username.
         *
         * <p>Pass {@code null} to unassign: Jira reads an explicit null here as "unassigned", and this is
         * one of the few places where sending null is meaningful rather than a mistake. Use
         * {@link #assignToDefault()} for the project's configured default assignee instead.
         *
         * @param username the Jira Server username, or {@code null} to unassign
         * @return this builder
         */
        public Builder assignee(String username) {
            return username == null ? clear("assignee") : field("assignee", JiraUser.named(username));
        }

        /**
         * Asks Jira to apply the project's default assignee rule, by sending the sentinel username
         * {@code -1}. Distinct from unassigning: the default may well be a person.
         *
         * @return this builder
         */
        public Builder assignToDefault() {
            return field("assignee", JiraUser.named("-1"));
        }

        /** Sets the reporter by username, which requires the "Modify Reporter" permission. */
        public Builder reporter(String username) {
            return field("reporter", JiraUser.named(username));
        }

        /** Sets the priority by name. */
        public Builder priority(String name) {
            return field("priority", Priority.named(name));
        }

        /** Sets the priority by id. */
        public Builder priorityId(String id) {
            return field("priority", Priority.byId(id));
        }

        /** Sets the resolution by name; normally only legal as part of a transition. */
        public Builder resolution(String name) {
            return field("resolution", Resolution.named(name));
        }

        /** Sets the issue security level by id. */
        public Builder securityLevelId(String id) {
            return field("security", IssueSecurityLevel.byId(id));
        }

        /** Sets the due date. */
        public Builder dueDate(LocalDate dueDate) {
            return field("duedate", dueDate == null ? null : dueDate.toString());
        }

        /** Sets the parent issue by key, for a subtask or a hierarchy link. */
        public Builder parent(String parentIssueKey) {
            return field("parent", Map.of("key", parentIssueKey));
        }

        /** Replaces the label set. */
        public Builder labels(String... labels) {
            return field("labels", List.of(labels));
        }

        /** Replaces the label set. */
        public Builder labels(Collection<String> labels) {
            return field("labels", List.copyOf(labels));
        }

        /** Adds one label, leaving the others alone. */
        public Builder addLabel(String label) {
            return operation("labels", "add", label);
        }

        /** Removes one label, leaving the others alone. */
        public Builder removeLabel(String label) {
            return operation("labels", "remove", label);
        }

        /** Replaces the component set, by name. */
        public Builder components(String... names) {
            return field("components", Arrays.stream(names).map(ProjectComponent::named).toList());
        }

        /** Adds one component by name. */
        public Builder addComponent(String name) {
            return operation("components", "add", ProjectComponent.named(name));
        }

        /** Removes one component by name. */
        public Builder removeComponent(String name) {
            return operation("components", "remove", ProjectComponent.named(name));
        }

        /** Replaces the fix version set, by name. */
        public Builder fixVersions(String... names) {
            return field("fixVersions", Arrays.stream(names).map(ProjectVersion::named).toList());
        }

        /** Adds one fix version by name. */
        public Builder addFixVersion(String name) {
            return operation("fixVersions", "add", ProjectVersion.named(name));
        }

        /** Removes one fix version by name. */
        public Builder removeFixVersion(String name) {
            return operation("fixVersions", "remove", ProjectVersion.named(name));
        }

        /** Replaces the affects-version set, by name. */
        public Builder affectsVersions(String... names) {
            return field("versions", Arrays.stream(names).map(ProjectVersion::named).toList());
        }

        /** Sets the time tracking composite. */
        public Builder timeTracking(TimeTracking timeTracking) {
            return field("timetracking", timeTracking);
        }

        /**
         * Adds a comment as part of the same request, which is the only way to comment atomically with an
         * edit or a transition - and the only way to comment on a transition screen that requires one.
         *
         * @param body comment text, in Jira wiki markup
         * @return this builder
         */
        public Builder addComment(String body) {
            return operation("comment", "add", Map.of("body", body));
        }

        /** Adds a comment restricted to a group or project role. */
        public Builder addComment(String body, ru.ludwigandreas.jira.model.common.Visibility visibility) {
            return operation("comment", "add", Map.of("body", body, "visibility", visibility));
        }

        /**
         * Sets any field by its API id.
         *
         * <p>The general escape hatch, and the right call for a custom field whose type this library does
         * not model. A {@code null} value is ignored rather than written - use {@link #clear(String)} to
         * mean "set this to null".
         *
         * @param fieldId the API id, for example {@code summary} or {@code customfield_10234}
         * @param value the value, serialized by Jackson
         * @return this builder
         */
        public Builder field(String fieldId, Object value) {
            if (value != null) {
                fields.put(fieldId, value);
            }
            return this;
        }

        /**
         * Explicitly sets a field to JSON null, which Jira reads as "clear this field".
         *
         * <p>Held as a {@link NullNode} rather than a Java {@code null} on purpose: the client's mapper
         * omits null values when serializing, so a Java null here would silently vanish from the payload
         * and the field would be left unchanged instead of cleared.
         *
         * @param fieldId the API id of the field to clear
         * @return this builder
         */
        public Builder clear(String fieldId) {
            fields.put(fieldId, NullNode.getInstance());
            return this;
        }

        /**
         * Adds a raw operation to the {@code update} section.
         *
         * @param fieldId the API id of the field to mutate
         * @param operationName {@code add}, {@code remove}, {@code set} or {@code edit}
         * @param value the operand
         * @return this builder
         */
        public Builder operation(String fieldId, String operationName, Object value) {
            Map<String, Object> op = new LinkedHashMap<>();
            op.put(operationName, value);
            update.computeIfAbsent(fieldId, key -> new ArrayList<>()).add(op);
            return this;
        }

        /** Sets an entity property alongside the issue write. */
        public Builder property(String key, Object value) {
            properties.add(new EntityPropertyInput(key, value));
            return this;
        }

        /** Builds the immutable payload. */
        public IssueInput build() {
            return new IssueInput(this);
        }
    }
}
