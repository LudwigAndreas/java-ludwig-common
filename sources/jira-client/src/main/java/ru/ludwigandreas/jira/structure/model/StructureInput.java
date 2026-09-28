package ru.ludwigandreas.jira.structure.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The payload for creating or updating a structure.
 *
 * <p>A structure created with no {@code permissions} is visible only to its owner - which, for one created
 * by an integration's service account, means nobody. State the permissions at creation time.
 *
 * @param name display name, required on create
 * @param description free text
 * @param editRequiresParentIssuePermission whether edits require Jira permissions on the parent issue
 * @param permissions the permission rules to set
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StructureInput(@JsonProperty("name") String name,
                             @JsonProperty("description") String description,
                             @JsonProperty("editRequiresParentIssuePermission")
                             Boolean editRequiresParentIssuePermission,
                             @JsonProperty("permissions") List<StructurePermissionRule> permissions) {

    /** A structure with a name and a permission rule set. */
    public static StructureInput of(String name, List<StructurePermissionRule> permissions) {
        return new StructureInput(name, null, null, permissions);
    }
}
