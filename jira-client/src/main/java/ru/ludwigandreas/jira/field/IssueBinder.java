package ru.ludwigandreas.jira.field;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ru.ludwigandreas.jira.error.JiraException;
import ru.ludwigandreas.jira.json.JiraJson;
import ru.ludwigandreas.jira.model.issue.Issue;

/**
 * Projects a Jira issue onto an application type annotated with {@link JiraFieldBinding}.
 *
 * <p>The point is to keep Jira's field ids out of the rest of a codebase. A service that works with
 * {@code Ticket(key, summary, storyPoints, team)} is readable and testable; one that threads
 * {@code issue.fields().raw("customfield_11702")} through three layers is neither, and breaks silently when
 * it is deployed against a different Jira.
 *
 * <pre>{@code
 * IssueBinder binder = client.binder();
 * List<Ticket> tickets = client.search().searchAll(query).map(i -> binder.bind(i, Ticket.class)).toList();
 * }</pre>
 *
 * <p>Implemented by rewriting the issue into a JSON object keyed by the target's property names and handing
 * that to Jackson, rather than by reflective field assignment. That means records, immutable classes,
 * Jackson's own annotations and its converters all keep working, and the binder does not need
 * {@code setAccessible}.
 *
 * <p>The binding plan for a type is computed once and cached, so the reflection cost is paid on the first
 * issue only. Thread-safe.
 */
public final class IssueBinder {

    private final ObjectMapper mapper;
    private final CustomFieldRegistry registry;
    private final Map<Class<?>, List<Binding>> plans = new ConcurrentHashMap<>();

    public IssueBinder(JiraJson json, CustomFieldRegistry registry) {
        this.mapper = json.objectMapper();
        this.registry = registry;
    }

    /**
     * Projects an issue onto an annotated type.
     *
     * @param issue the issue to read
     * @param type the target type, whose properties carry {@link JiraFieldBinding}
     * @param <T> the target type
     * @return a new instance of the target type
     * @throws JiraException when the type declares no bindings, or a name does not resolve
     */
    public <T> T bind(Issue issue, Class<T> type) {
        List<Binding> plan = plans.computeIfAbsent(type, this::planFor);
        ObjectNode target = mapper.createObjectNode();
        JsonNode fields = issue.fields() == null
                ? mapper.createObjectNode()
                : mapper.valueToTree(issue.fields());
        for (Binding binding : plan) {
            JsonNode value = switch (binding.fieldId()) {
                case "key" -> mapper.getNodeFactory().textNode(issue.key());
                case "id" -> mapper.getNodeFactory().textNode(issue.id());
                case "self" -> mapper.getNodeFactory().textNode(issue.self());
                default -> fields.get(binding.fieldId());
            };
            if (value != null && !value.isNull()) {
                target.set(binding.propertyName(), value);
            }
        }
        return mapper.convertValue(target, type);
    }

    private List<Binding> planFor(Class<?> type) {
        List<Binding> bindings = new ArrayList<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                resolve(component, component.getName(), type).ifPresent(bindings::add);
            }
        } else {
            for (Field field : type.getDeclaredFields()) {
                if (!field.isSynthetic()) {
                    resolve(field, field.getName(), type).ifPresent(bindings::add);
                }
            }
        }
        if (bindings.isEmpty()) {
            throw new JiraException(type.getName() + " declares no @JiraFieldBinding properties, so there is "
                    + "nothing to bind an issue onto");
        }
        return List.copyOf(bindings);
    }

    private java.util.Optional<Binding> resolve(AnnotatedElement element, String propertyName, Class<?> type) {
        JiraFieldBinding annotation = element.getAnnotation(JiraFieldBinding.class);
        if (annotation == null) {
            return java.util.Optional.empty();
        }
        boolean hasId = !annotation.id().isEmpty();
        boolean hasName = !annotation.name().isEmpty();
        if (hasId == hasName) {
            throw new JiraException("@JiraFieldBinding on " + type.getName() + "." + propertyName
                    + " must set exactly one of id and name, not " + (hasId ? "both" : "neither"));
        }
        String fieldId = hasId ? annotation.id() : resolveName(annotation.name(), type, propertyName);
        return java.util.Optional.of(new Binding(propertyName, fieldId));
    }

    private String resolveName(String displayName, Class<?> type, String propertyName) {
        if (registry == null) {
            throw new JiraException("@JiraFieldBinding(name = \"" + displayName + "\") on " + type.getName() + "."
                    + propertyName + " needs a CustomFieldRegistry to resolve the name to an id; build the "
                    + "client with one, or annotate with id instead");
        }
        return registry.requireId(displayName);
    }

    /** One property of the target type and the Jira field it reads from. */
    private record Binding(String propertyName, String fieldId) {
    }
}
