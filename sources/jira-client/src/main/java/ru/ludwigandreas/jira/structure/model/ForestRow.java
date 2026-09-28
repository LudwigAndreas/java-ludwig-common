package ru.ludwigandreas.jira.structure.model;

import java.util.Optional;

/**
 * One row of a Structure forest, decoded from the response's {@code formula} string.
 *
 * <p>A row is not an issue. It is a <em>position</em> in the structure that points at an item, and the same
 * issue can occupy several rows in one structure. That is why every Structure write addresses a
 * {@code rowId} rather than an issue key: moving "the issue" is meaningless when it appears three times.
 *
 * @param rowId the row's identity within the forest, and the handle every forest action takes
 * @param depth nesting depth, zero at the top level
 * @param itemTypeIndex index into the response's {@code itemTypes} map, or {@code null} for a plain issue
 * @param itemId the item's id: an issue id when {@code itemTypeIndex} is absent, otherwise the type's own id
 * @param stringItemId set instead of {@code itemId} for item types whose ids are strings
 * @param semantics Structure's internal row semantics, when the formula carried them
 */
public record ForestRow(long rowId,
                        int depth,
                        Integer itemTypeIndex,
                        Long itemId,
                        String stringItemId,
                        String semantics) {

    /** Whether this row points at a Jira issue rather than at a folder, generator or app-defined item. */
    public boolean isIssue() {
        return itemTypeIndex == null;
    }

    /** The issue id this row points at, empty for any other item type. */
    public Optional<Long> issueId() {
        return isIssue() ? Optional.ofNullable(itemId) : Optional.empty();
    }
}
