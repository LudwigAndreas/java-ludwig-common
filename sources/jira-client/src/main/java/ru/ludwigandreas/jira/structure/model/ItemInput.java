package ru.ludwigandreas.jira.structure.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * The item half of a Structure item create or update: what kind of thing it is, and its values.
 *
 * <p>Structure's own item types are the ones a caller normally creates. A folder is
 * {@code com.almworks.jira.structure:type-folder} with a {@code summary} value; issues are not created
 * here at all - they are created through Jira and then added to a forest.
 *
 * @param type the Structure item type key, required on create
 * @param itemId the item to change, required on update
 * @param values the item's values, keyed by the type's own value names
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ItemInput(@JsonProperty("type") String type,
                        @JsonProperty("itemId") String itemId,
                        @JsonProperty("values") Map<String, Object> values) {

    /** The Structure item type key for a folder. */
    public static final String TYPE_FOLDER = "com.almworks.jira.structure:type-folder";

    /** A new folder with a name. */
    public static ItemInput folder(String summary) {
        return new ItemInput(TYPE_FOLDER, null, Map.of("summary", summary));
    }

    /** An update that renames an existing item. */
    public static ItemInput rename(String itemId, String summary) {
        return new ItemInput(null, itemId, Map.of("summary", summary));
    }
}
