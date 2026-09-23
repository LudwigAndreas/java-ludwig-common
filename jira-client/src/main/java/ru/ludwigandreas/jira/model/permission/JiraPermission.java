package ru.ludwigandreas.jira.model.permission;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One permission and, for {@code /mypermissions}, whether the calling user holds it.
 *
 * <p>{@code key} is the stable identifier ({@code BROWSE_PROJECTS}, {@code EDIT_ISSUES},
 * {@code TRANSITION_ISSUES}) and is what code should test; {@code name} is localized display text and
 * changes with the caller's language.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraPermission(String id,
                             String key,
                             String name,
                             String type,
                             String description,
                             Boolean havePermission,
                             Boolean deprecatedKey) {

    /** Whether the calling user holds this permission in the queried context. */
    public boolean isGranted() {
        return Boolean.TRUE.equals(havePermission);
    }
}
