package ru.ludwigandreas.jira.api;

import java.util.List;
import java.util.Optional;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.http.JiraPaths;
import ru.ludwigandreas.jira.model.common.EntityProperty;

/**
 * Entity properties on an issue: arbitrary JSON an integration stores against an issue without a custom
 * field.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#issueProperties()}.
 *
 * <p>The right place for integration state - a remote system's record id, a sync checkpoint - because it is
 * invisible in the UI, does not need an administrator to create it, and does not consume a custom field.
 * The trade is that it is not indexed: there is no JQL over entity properties on Jira Server, so anything
 * that has to be searchable belongs in a custom field instead.
 */
public final class IssuePropertyApi {

    private static final String ISSUE = ApiPaths.API_2 + "/issue";

    private final JiraRestClient rest;

    public IssuePropertyApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /** The property keys set on an issue, without their values. */
    public List<String> keys(String issueKeyOrId) {
        return rest.get(JiraPaths.of(ISSUE, issueKeyOrId, "properties"))
                .operation("issueproperty.keys")
                .as(PropertyKeys.class)
                .keys()
                .stream()
                .map(PropertyKeys.Key::key)
                .toList();
    }

    /** Reads one property, empty when the issue does not carry that key. */
    public Optional<EntityProperty> get(String issueKeyOrId, String propertyKey) {
        return rest.get(JiraPaths.of(ISSUE, issueKeyOrId, "properties", propertyKey))
                .operation("issueproperty.get")
                .asOptional(EntityProperty.class);
    }

    /** Reads one property's value as a concrete type, empty when the key is not set. */
    public <T> Optional<T> get(String issueKeyOrId, String propertyKey, Class<T> type) {
        return get(issueKeyOrId, propertyKey)
                .map(EntityProperty::value)
                .map(node -> rest.json().convert(
                        node, rest.json().objectMapper().getTypeFactory().constructType(type)));
    }

    /** Sets a property, replacing any previous value under the same key. */
    public void set(String issueKeyOrId, String propertyKey, Object value) {
        rest.put(JiraPaths.of(ISSUE, issueKeyOrId, "properties", propertyKey))
                .operation("issueproperty.set")
                .body(value)
                .asVoid();
    }

    /** Deletes a property. */
    public void delete(String issueKeyOrId, String propertyKey) {
        rest.delete(JiraPaths.of(ISSUE, issueKeyOrId, "properties", propertyKey))
                .operation("issueproperty.delete")
                .asVoid();
    }

    /** Jira's key-list envelope. */
    private record PropertyKeys(List<Key> keys) {

        PropertyKeys {
            keys = keys == null ? List.of() : List.copyOf(keys);
        }

        /** One key, with the URL of the property it names. */
        record Key(String self, String key) {
        }
    }
}
