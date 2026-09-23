package ru.ludwigandreas.jira.model.field;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The declared type of a field, as Jira reports it on {@code /field}, {@code /editmeta} and
 * {@code /createmeta}.
 *
 * <p>This is what makes generic custom field handling possible. {@code type} is the value type
 * ({@code string}, {@code number}, {@code datetime}, {@code option}, {@code array}, {@code user},
 * {@code version}...); {@code items} names the element type when {@code type} is {@code array};
 * {@code custom} is the full plugin key of the custom field type
 * ({@code com.atlassian.jira.plugin.system.customfieldtypes:datepicker}) and {@code customId} its numeric
 * id, so {@code customfield_<customId>} is the field's JSON key.
 *
 * <p>{@code system} is populated instead of {@code custom} for built-in fields and holds the system field
 * name ({@code summary}, {@code issuetype}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldSchema(String type, String items, String system, String custom, Long customId) {

    /** Whether this schema describes a custom field rather than a built-in one. */
    public boolean isCustom() {
        return custom != null;
    }

    /** Whether values of this field are collections. */
    public boolean isArray() {
        return "array".equals(type);
    }

    /** The element type for an array field, or the value type itself for a scalar one. */
    public String effectiveType() {
        return isArray() && items != null ? items : type;
    }
}
