package ru.ludwigandreas.jira.model.project;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * A project as it appears embedded in an issue or a search result: enough to identify it, not the full
 * project resource.
 *
 * <p>Separate from {@link Project} because the embedded form genuinely carries fewer fields, and a single
 * type would have to declare every one of them nullable - which hides from the caller which fields are
 * actually populated by which endpoint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectRef(String self, String id, String key, String name,
                         String projectTypeKey, Map<String, String> avatarUrls) {

    /** A reference by key, for a create-issue payload. */
    public static ProjectRef byKey(String key) {
        return new ProjectRef(null, null, key, null, null, null);
    }

    /** A reference by numeric id, for the endpoints that will not take a key. */
    public static ProjectRef byId(String id) {
        return new ProjectRef(null, id, null, null, null, null);
    }
}
