package ru.ludwigandreas.jira.structure.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One edit to a forest: add rows, move a row, or remove one.
 *
 * <p>Position is expressed the way Structure expresses it, with three optional anchors rather than an
 * index: {@code under} names the new parent row, {@code after} the sibling to follow, {@code before} the
 * sibling to precede. Zero means "no anchor" - {@code under: 0} places a row at the top level. Indexes are
 * deliberately absent from the API, because an index computed from a forest read a moment ago is wrong the
 * instant anything else moves a row; an anchor row id is not.
 *
 * @param action {@code add}, {@code move} or {@code remove}
 * @param rowId the row being moved or removed
 * @param under row id of the new parent, or 0 for the top level
 * @param after row id of the sibling to place this after
 * @param before row id of the sibling to place this before
 * @param forest for {@code add}, the rows to insert, in the formula encoding
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ForestAction(@JsonProperty("action") String action,
                           @JsonProperty("rowId") Long rowId,
                           @JsonProperty("under") Long under,
                           @JsonProperty("after") Long after,
                           @JsonProperty("before") Long before,
                           @JsonProperty("forest") String forest) {

    /**
     * Adds rows under a parent, at the end of its children.
     *
     * @param forestFormula the rows to add, encoded as a formula
     * @param underRowId the parent row, or 0 for the top level
     * @return the action
     */
    public static ForestAction add(String forestFormula, long underRowId) {
        return new ForestAction("add", null, underRowId, 0L, 0L, forestFormula);
    }

    /**
     * Moves a row under a new parent, after a given sibling.
     *
     * @param rowId the row to move
     * @param underRowId the new parent row, or 0 for the top level
     * @param afterRowId the sibling to follow, or 0 to become the first child
     * @return the action
     */
    public static ForestAction move(long rowId, long underRowId, long afterRowId) {
        return new ForestAction("move", rowId, underRowId, afterRowId, 0L, null);
    }

    /** Removes a row, and with it everything nested under it. */
    public static ForestAction remove(long rowId) {
        return new ForestAction("remove", rowId, null, null, null, null);
    }
}
