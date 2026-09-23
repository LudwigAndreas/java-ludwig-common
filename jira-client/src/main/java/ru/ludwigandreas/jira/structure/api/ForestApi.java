package ru.ludwigandreas.jira.structure.api;

import java.util.List;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.api.ApiPaths;
import ru.ludwigandreas.jira.structure.model.Forest;
import ru.ludwigandreas.jira.structure.model.ForestAction;
import ru.ludwigandreas.jira.structure.model.ForestSpec;
import ru.ludwigandreas.jira.structure.model.ForestUpdateRequest;
import ru.ludwigandreas.jira.structure.model.ForestUpdateResult;

/**
 * Reading and changing the content of a Structure forest.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#forests()}.
 *
 * <p>The read is a {@code POST} to {@code /forest/latest} rather than the documented {@code GET} with the
 * specification in a {@code ?s=} query parameter. Both work; the {@code POST} is used here because a
 * specification carrying transformations is long, URL-encoded JSON in a query string is exactly the thing
 * reverse proxies truncate, and a truncated specification is not an error - it is a different forest. The
 * request is marked retryable, since it is a read whatever its method.
 *
 * <p>A change is always made against a version, and Structure rejects one made against a stale version
 * rather than merging it - see {@link ru.ludwigandreas.jira.structure.model.ForestVersion}. The correct
 * response to that rejection is to re-read and recompute, which is what {@link #move} does in one step.
 */
public final class ForestApi {

    private static final String FOREST = ApiPaths.STRUCTURE_2 + "/forest";

    private final JiraRestClient rest;

    public ForestApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /**
     * Reads a forest's current content.
     *
     * @param spec which forest to read
     * @return the forest, whose {@code formula} decodes to rows through {@link Forest#rows()}
     */
    public Forest read(ForestSpec spec) {
        return rest.post(FOREST + "/latest")
                .operation("structure.forest.read")
                .retryable(true)
                .body(spec)
                .as(Forest.class);
    }

    /** Reads the whole content of a structure by id. */
    public Forest readStructure(long structureId) {
        return read(ForestSpec.structure(structureId));
    }

    /**
     * Applies a set of edits to a forest.
     *
     * @param request the forest, the version observed, and the edits
     * @return what Structure applied, including the ids assigned to any added rows
     */
    public ForestUpdateResult update(ForestUpdateRequest request) {
        return rest.post(FOREST + "/update")
                .operation("structure.forest.update")
                .body(request)
                .as(ForestUpdateResult.class);
    }

    /**
     * Reads the forest's current version and applies the edits against it in one call.
     *
     * <p>Convenient, and narrower than it looks: it closes the window between reading and writing to a
     * single round trip, but it does not eliminate it. A caller whose edits depend on the forest's contents
     * - "move every row under the one named X" - must read the forest itself, compute from what it read,
     * and send that version, so that a concurrent change is rejected rather than applied to a forest that
     * no longer matches the plan.
     *
     * @param spec which forest to change
     * @param actions the edits
     * @return what Structure applied
     */
    public ForestUpdateResult apply(ForestSpec spec, List<ForestAction> actions) {
        Forest current = read(spec);
        return update(new ForestUpdateRequest(spec, current.version(), actions));
    }

    /**
     * Moves one row under a new parent.
     *
     * @param spec which forest to change
     * @param rowId the row to move
     * @param underRowId the new parent row, or 0 for the top level
     * @param afterRowId the sibling to follow, or 0 to become the first child
     * @return what Structure applied
     */
    public ForestUpdateResult move(ForestSpec spec, long rowId, long underRowId, long afterRowId) {
        return apply(spec, List.of(ForestAction.move(rowId, underRowId, afterRowId)));
    }

    /**
     * Removes a row and everything nested under it.
     *
     * <p>Removes the row from the structure. It does not delete the issue the row points at, and it does
     * delete any folder item the row was the only reference to.
     *
     * @param spec which forest to change
     * @param rowId the row to remove
     * @return what Structure applied
     */
    public ForestUpdateResult remove(ForestSpec spec, long rowId) {
        return apply(spec, List.of(ForestAction.remove(rowId)));
    }

    /**
     * Adds issues to a forest, under a parent row.
     *
     * <p>The rows are expressed in Structure's formula encoding, built here from the issue ids: each issue
     * becomes a top-level row of the inserted fragment with its own negative placeholder row id, and
     * Structure reports the real ids it assigned in
     * {@link ForestUpdateResult#assignedRowIds()}.
     *
     * @param spec which forest to change
     * @param underRowId the parent row to add under, or 0 for the top level
     * @param issueIds numeric issue ids - not keys; Structure's formula addresses issues by id
     * @return what Structure applied, including the assigned row ids
     */
    public ForestUpdateResult addIssues(ForestSpec spec, long underRowId, List<Long> issueIds) {
        if (issueIds.isEmpty()) {
            throw new IllegalArgumentException("No issue ids to add");
        }
        // Each inserted row needs its OWN placeholder id. Reusing one for every row would make them
        // indistinguishable to Structure and to the id mapping it sends back.
        StringBuilder formula = new StringBuilder();
        for (int i = 0; i < issueIds.size(); i++) {
            if (i > 0) {
                formula.append(',');
            }
            formula.append(-(i + 1)).append(":0:").append(issueIds.get(i));
        }
        return apply(spec, List.of(ForestAction.add(formula.toString(), underRowId)));
    }
}
