package ru.ludwigandreas.jira.api;

import java.util.List;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.http.JiraPaths;
import ru.ludwigandreas.jira.model.field.CustomFieldOption;
import ru.ludwigandreas.jira.model.field.FieldDefinition;

/**
 * Field definitions: every system and custom field the instance defines.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#fields()}. This is the input to
 * {@link ru.ludwigandreas.jira.field.CustomFieldRegistry}, which is the intended way to consume it - a name
 * resolved once at startup beats {@code "customfield_10234"} written into application code, and beats
 * calling this endpoint per request.
 */
public final class FieldApi {

    private static final String FIELD = ApiPaths.API_2 + "/field";

    private final JiraRestClient rest;

    public FieldApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /**
     * Every field defined on the instance, system and custom.
     *
     * <p>Instance-wide and unfiltered: a field that appears here may still be absent from any given issue's
     * screens. {@link IssueApi#editMeta(String)} is what answers "may I set this on this issue".
     *
     * @return every field definition
     */
    public List<FieldDefinition> list() {
        return rest.get(FIELD)
                .operation("field.list")
                .as(new com.fasterxml.jackson.core.type.TypeReference<List<FieldDefinition>>() { });
    }

    /** One option of a select-style custom field, by option id. */
    public CustomFieldOption option(String optionId) {
        return rest.get(JiraPaths.of(ApiPaths.API_2 + "/customFieldOption", optionId))
                .operation("field.option.get")
                .as(CustomFieldOption.class);
    }
}
