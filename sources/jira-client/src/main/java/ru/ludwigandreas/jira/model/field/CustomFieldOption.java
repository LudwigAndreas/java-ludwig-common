package ru.ludwigandreas.jira.model.field;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One option of a select, radio, checkbox or cascading-select custom field.
 *
 * <p>A value is set by sending {@code {"id": "10100"}} or {@code {"value": "Blue"}}. Prefer the id: option
 * values are renamed by administrators, and a payload built around the display text starts failing the day
 * that happens. {@code child} carries the second level of a cascading select.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomFieldOption(String self, String id, String value, CustomFieldOption child) {

    /** A reference by id, which is the form that survives a rename. */
    public static CustomFieldOption byId(String id) {
        return new CustomFieldOption(null, id, null, null);
    }

    /** A reference by display value. */
    public static CustomFieldOption ofValue(String value) {
        return new CustomFieldOption(null, null, value, null);
    }

    /** A cascading-select value: parent option plus the child option under it. */
    public static CustomFieldOption cascading(String parentId, String childId) {
        return new CustomFieldOption(null, parentId, null, byId(childId));
    }
}
