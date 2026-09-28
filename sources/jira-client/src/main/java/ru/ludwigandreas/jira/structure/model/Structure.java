package ru.ludwigandreas.jira.structure.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A Structure structure: the named container a forest belongs to.
 *
 * <p>{@code editRequiresParentIssuePermission} is the flag that decides whether rearranging rows needs the
 * Jira edit permission on the parent issue, or only the Structure edit permission. Turning it off makes a
 * structure editable by people who cannot edit the issues in it, which is usually intended for a planning
 * structure and usually not for one that mirrors an issue hierarchy.
 *
 * @param id the structure's id
 * @param name display name
 * @param description free text
 * @param editRequiresParentIssuePermission whether edits require Jira permissions on the parent issue
 * @param owner the owner, in Structure's {@code user:<username>} form
 * @param readOnly whether the calling user may only view it
 * @param permissions the permission rules, present only when asked for
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Structure(long id,
                        String name,
                        String description,
                        Boolean editRequiresParentIssuePermission,
                        String owner,
                        Boolean readOnly,
                        List<StructurePermissionRule> permissions) {

    /** Normalizes {@code permissions} to an immutable empty list rather than {@code null}. */
    public Structure {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
