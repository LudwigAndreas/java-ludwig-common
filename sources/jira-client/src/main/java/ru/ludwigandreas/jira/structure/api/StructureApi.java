package ru.ludwigandreas.jira.structure.api;

import java.util.List;
import java.util.Optional;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.api.ApiPaths;
import ru.ludwigandreas.jira.http.JiraPaths;
import ru.ludwigandreas.jira.structure.model.Structure;
import ru.ludwigandreas.jira.structure.model.StructureInput;

/**
 * Structures: the named containers of the ALM Works Structure app.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#structures()}.
 *
 * <p>This namespace exists only where the Structure app is installed and licensed. On an instance without
 * it every call here comes back 404, which is indistinguishable from a wrong path - so a service that
 * optionally integrates with Structure should probe once at startup with {@link #list()} rather than
 * discovering it per request.
 *
 * <p>Note that updates are {@code POST .../update} rather than {@code PUT}. That is Structure's API, not a
 * transcription error.
 */
public final class StructureApi {

    private static final String STRUCTURE = ApiPaths.STRUCTURE_2 + "/structure";

    private final JiraRestClient rest;

    public StructureApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /** Every structure the calling user can see. */
    public List<Structure> list() {
        return list(null, null, false);
    }

    /**
     * Structures matching a filter.
     *
     * @param nameFilter case-insensitive substring of the name, or {@code null} for all
     * @param permission minimum access level: {@code VIEW}, {@code EDIT} or {@code ADMIN}; {@code null} for any
     * @param includeArchived whether to include archived structures
     * @return the matching structures
     */
    public List<Structure> list(String nameFilter, String permission, boolean includeArchived) {
        return rest.get(STRUCTURE)
                .operation("structure.list")
                .query("name", nameFilter)
                .query("permission", permission)
                .query("archived", includeArchived ? Boolean.TRUE : null)
                .query("withOwner", Boolean.TRUE)
                .as(StructureList.class)
                .structures();
    }

    /** Reads one structure, with its permission rules and owner. */
    public Structure get(long structureId) {
        return rest.get(JiraPaths.of(STRUCTURE, structureId))
                .operation("structure.get")
                .query("withPermissions", Boolean.TRUE)
                .query("withOwner", Boolean.TRUE)
                .as(Structure.class);
    }

    /** Reads one structure, answering empty rather than throwing when it does not exist or is not visible. */
    public Optional<Structure> find(long structureId) {
        return rest.get(JiraPaths.of(STRUCTURE, structureId))
                .operation("structure.get")
                .query("withPermissions", Boolean.TRUE)
                .asOptional(Structure.class);
    }

    /** Finds a structure by exact name, empty when none matches and the first when several do. */
    public Optional<Structure> findByName(String name) {
        return list(name, null, false).stream()
                .filter(structure -> name.equalsIgnoreCase(structure.name()))
                .findFirst();
    }

    /** Creates a structure. */
    public Structure create(StructureInput input) {
        return rest.post(STRUCTURE).operation("structure.create").body(input).as(Structure.class);
    }

    /** Updates a structure's name, description or permissions. */
    public Structure update(long structureId, StructureInput input) {
        return rest.post(JiraPaths.of(STRUCTURE, structureId, "update"))
                .operation("structure.update")
                .body(input)
                .as(Structure.class);
    }

    /** Deletes a structure, and with it the arrangement of every row in it. */
    public void delete(long structureId) {
        rest.delete(JiraPaths.of(STRUCTURE, structureId)).operation("structure.delete").asVoid();
    }

    /** Structure's list envelope. */
    private record StructureList(List<Structure> structures) {

        StructureList {
            structures = structures == null ? List.of() : List.copyOf(structures);
        }
    }
}
