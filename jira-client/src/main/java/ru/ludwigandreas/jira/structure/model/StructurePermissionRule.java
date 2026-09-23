package ru.ludwigandreas.jira.structure.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One permission rule on a structure.
 *
 * <p>Two shapes share this record because Structure's API does. A {@code set} rule grants a level to a
 * subject - a group, a project role, a named user, or anyone - and the subject decides which of
 * {@code groupId}, {@code projectId}/{@code roleId} and {@code username} is populated. An {@code apply}
 * rule instead copies another structure's permissions, which is how an organisation keeps a family of
 * structures governed by one place.
 *
 * <p>Levels are {@code VIEW}, {@code EDIT}, {@code ADMIN} and {@code NONE}; {@code NONE} is a denial, not
 * an absence, and it overrides a grant from a broader rule.
 *
 * @param rule {@code set} or {@code apply}
 * @param subject {@code group}, {@code projectRole}, {@code user} or {@code anyone}
 * @param groupId group name, for a group subject
 * @param projectId project id, for a project-role subject
 * @param roleId role id, for a project-role subject
 * @param username username, for a user subject
 * @param level {@code VIEW}, {@code EDIT}, {@code ADMIN} or {@code NONE}
 * @param structureId the structure whose permissions are copied, for an {@code apply} rule
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record StructurePermissionRule(String rule,
                                      String subject,
                                      String groupId,
                                      Long projectId,
                                      Long roleId,
                                      String username,
                                      String level,
                                      Long structureId) {

    /** Grants a level to the members of a group. */
    public static StructurePermissionRule group(String groupName, String level) {
        return new StructurePermissionRule("set", "group", groupName, null, null, null, level, null);
    }

    /** Grants a level to a named user. */
    public static StructurePermissionRule user(String username, String level) {
        return new StructurePermissionRule("set", "user", null, null, null, username, level, null);
    }

    /** Grants a level to the holders of a project role. */
    public static StructurePermissionRule projectRole(long projectId, long roleId, String level) {
        return new StructurePermissionRule("set", "projectRole", null, projectId, roleId, null, level, null);
    }

    /** Grants a level to everyone who can log in. */
    public static StructurePermissionRule anyone(String level) {
        return new StructurePermissionRule("set", "anyone", null, null, null, null, level, null);
    }

    /** Copies another structure's permission rules instead of stating any. */
    public static StructurePermissionRule apply(long sourceStructureId) {
        return new StructurePermissionRule("apply", null, null, null, null, null, null, sourceStructureId);
    }
}
