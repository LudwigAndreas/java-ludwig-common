package ru.ludwigandreas.jira.structure.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * The payload for creating or updating a Structure item, and - for a create - placing it in a forest in the
 * same call.
 *
 * <p>{@code rowId} on a create is a caller-chosen <em>negative placeholder</em>. Structure assigns the real
 * row id and reports the mapping in
 * {@link ForestUpdateResult#assignedRowIds()}; the placeholder has no meaning afterwards. Using a positive
 * number here collides with an existing row.
 *
 * @param item what to create or change
 * @param forest which forest to place it in, with the version the caller observed
 * @param items the item-set version the caller observed
 * @param rowId negative placeholder id for the new row
 * @param under row id of the parent to place it under, or 0 for the top level
 * @param after row id of the sibling to follow, or 0
 * @param before row id of the sibling to precede, or 0
 * @param parameters type-specific extras
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ItemActionRequest(@JsonProperty("item") ItemInput item,
                                @JsonProperty("forest") ForestReference forest,
                                @JsonProperty("items") VersionReference items,
                                @JsonProperty("rowId") Long rowId,
                                @JsonProperty("under") Long under,
                                @JsonProperty("after") Long after,
                                @JsonProperty("before") Long before,
                                @JsonProperty("parameters") Map<String, Object> parameters) {

    /**
     * Creates an item and places it at the top level of a structure.
     *
     * @param item the item to create
     * @param spec the forest to place it in
     * @param version the forest version the caller observed
     * @param placeholderRowId a negative placeholder for the new row
     * @return the payload
     */
    public static ItemActionRequest create(ItemInput item, ForestSpec spec, ForestVersion version,
                                           long placeholderRowId) {
        return new ItemActionRequest(item, new ForestReference(spec, version),
                new VersionReference(ForestVersion.zero()), placeholderRowId, 0L, 0L, 0L, null);
    }

    /**
     * Updates an item's values, with no change to any forest.
     *
     * @param item the item to change
     * @return the payload
     */
    public static ItemActionRequest update(ItemInput item) {
        return new ItemActionRequest(item, null, new VersionReference(ForestVersion.zero()),
                null, null, null, null, null);
    }

    /** A forest plus the version of it the caller observed. */
    public record ForestReference(@JsonProperty("spec") ForestSpec spec,
                                  @JsonProperty("version") ForestVersion version) {
    }

    /** A bare version reference, which Structure uses for the item set. */
    public record VersionReference(@JsonProperty("version") ForestVersion version) {
    }
}
