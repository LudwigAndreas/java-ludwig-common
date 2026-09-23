package ru.ludwigandreas.jira.structure.api;

import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.api.ApiPaths;
import ru.ludwigandreas.jira.structure.model.ForestSpec;
import ru.ludwigandreas.jira.structure.model.ForestUpdateResult;
import ru.ludwigandreas.jira.structure.model.ForestVersion;
import ru.ludwigandreas.jira.structure.model.ItemActionRequest;
import ru.ludwigandreas.jira.structure.model.ItemInput;

/**
 * Structure items that are not Jira issues - folders, and whatever item types the installed extensions
 * define.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#structureItems()}.
 *
 * <p>Issues are not created here. An issue is created through Jira and then <em>added</em> to a forest with
 * {@link ForestApi#addIssues}; this endpoint is for the items that exist only inside Structure, of which
 * folders are the one anybody uses.
 *
 * <p>A create both makes the item and places it, because a Structure item with no row is unreachable. The
 * placeholder row id and the assigned one come back in
 * {@link ForestUpdateResult#assignedRowIds()}.
 */
public final class StructureItemApi {

    private static final String ITEM = ApiPaths.STRUCTURE_2 + "/item";

    /**
     * The placeholder row id used for a newly created item. Negative because Structure reserves negative
     * ids for placeholders and rejects a positive one that collides with a real row.
     */
    private static final long PLACEHOLDER_ROW_ID = -100L;

    private final JiraRestClient rest;
    private final ForestApi forests;

    public StructureItemApi(JiraRestClient rest, ForestApi forests) {
        this.rest = rest;
        this.forests = forests;
    }

    /**
     * Creates an item and places it in a forest.
     *
     * @param request the item, its placement, and the versions the caller observed
     * @return what Structure applied, including the assigned row id
     */
    public ForestUpdateResult create(ItemActionRequest request) {
        return rest.post(ITEM + "/create")
                .operation("structure.item.create")
                .body(request)
                .as(ForestUpdateResult.class);
    }

    /**
     * Creates a folder at the top level of a structure, reading the forest's current version first.
     *
     * @param spec the forest to create it in
     * @param name the folder's name
     * @return what Structure applied; {@link ForestUpdateResult#assignedRowIds()} holds the new row's id
     */
    public ForestUpdateResult createFolder(ForestSpec spec, String name) {
        ForestVersion version = forests.read(spec).version();
        return create(ItemActionRequest.create(ItemInput.folder(name), spec, version, PLACEHOLDER_ROW_ID));
    }

    /**
     * Changes an existing item's values.
     *
     * @param request the item and its new values
     * @return what Structure applied
     */
    public ForestUpdateResult update(ItemActionRequest request) {
        return rest.post(ITEM + "/update")
                .operation("structure.item.update")
                .body(request)
                .as(ForestUpdateResult.class);
    }

    /** Renames an item. */
    public ForestUpdateResult rename(String itemId, String newName) {
        return update(ItemActionRequest.update(ItemInput.rename(itemId, newName)));
    }
}
