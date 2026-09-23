package ru.ludwigandreas.jira.api;

import java.util.Map;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.http.JiraPaths;
import ru.ludwigandreas.jira.model.project.ProjectComponent;
import ru.ludwigandreas.jira.request.ComponentInput;

/**
 * Project components.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#components()}. Listing a project's components
 * lives on {@link ProjectApi#components(String)}.
 */
public final class ComponentApi {

    private static final String COMPONENT = ApiPaths.API_2 + "/component";

    private final JiraRestClient rest;

    public ComponentApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /** Reads one component. */
    public ProjectComponent get(String componentId) {
        return rest.get(JiraPaths.of(COMPONENT, componentId))
                .operation("component.get")
                .as(ProjectComponent.class);
    }

    /** Creates a component. */
    public ProjectComponent create(ComponentInput input) {
        return rest.post(COMPONENT).operation("component.create").body(input).as(ProjectComponent.class);
    }

    /** Updates a component. */
    public ProjectComponent update(String componentId, ComponentInput input) {
        return rest.put(JiraPaths.of(COMPONENT, componentId))
                .operation("component.update")
                .body(input)
                .as(ProjectComponent.class);
    }

    /**
     * Deletes a component, optionally moving its issues to another one.
     *
     * @param componentId the component to delete
     * @param moveIssuesTo id of the component to move issues to, or {@code null} to leave them with none
     */
    public void delete(String componentId, String moveIssuesTo) {
        rest.delete(JiraPaths.of(COMPONENT, componentId))
                .operation("component.delete")
                .query("moveIssuesTo", moveIssuesTo)
                .asVoid();
    }

    /** How many issues reference this component - what to check before deleting it. */
    public int issueCount(String componentId) {
        Map<String, Object> counts = rest.get(JiraPaths.of(COMPONENT, componentId, "relatedIssueCounts"))
                .operation("component.issuecount")
                .as(new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        Object count = counts.get("issueCount");
        return count instanceof Number number ? number.intValue() : 0;
    }
}
