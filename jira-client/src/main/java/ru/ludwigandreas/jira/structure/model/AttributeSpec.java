package ru.ludwigandreas.jira.structure.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Which column to compute for a set of rows, in a Structure value request.
 *
 * <p>An attribute is Structure's name for a column: a Jira field passed through, or something Structure
 * derives - a sum rolled up over children, a progress percentage, a formula's result. This is what makes
 * the value endpoint worth using at all: those derived values exist nowhere in Jira's own API, so a
 * "total story points of this epic's subtree" can only be read from here.
 *
 * <p>{@code format} decides the shape of the returned value ({@code text}, {@code number}, {@code html}),
 * and {@code params} carries whatever the attribute needs - a field id for a field attribute, an
 * aggregation mode for a rollup.
 *
 * @param id the attribute id
 * @param format the requested value format
 * @param params attribute-specific parameters
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttributeSpec(@JsonProperty("id") String id,
                            @JsonProperty("format") String format,
                            @JsonProperty("params") Map<String, Object> params) {

    /** An attribute with no parameters, in the given format. */
    public static AttributeSpec of(String id, String format) {
        return new AttributeSpec(id, format, null);
    }

    /** A Jira field passed through as text, which is the commonest request. */
    public static AttributeSpec field(String fieldId) {
        return new AttributeSpec("field", "text", Map.of("id", fieldId));
    }
}
