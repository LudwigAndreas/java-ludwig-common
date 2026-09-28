package ru.ludwigandreas.jira.structure.model;

import java.util.ArrayList;
import java.util.List;
import ru.ludwigandreas.jira.error.JiraSerializationException;

/**
 * Decodes Structure's {@code formula} string into rows.
 *
 * <p>Structure does not send a forest as nested JSON. It sends one string holding every row, because a
 * structure of fifty thousand rows as nested objects would be tens of megabytes - the formula for the same
 * forest is a few hundred kilobytes. The encoding, from the Structure 2.0 Forest Resource documentation, is
 * a comma-separated list of {@code rowId:depth:itemIdentity} triples with an optional fourth field carrying
 * Structure's internal row semantics, where the item identity is one of:
 *
 * <ul>
 *   <li>a bare number - a Jira issue id;</li>
 *   <li>{@code <typeIndex>/<id>} - an item of another type with a numeric id;</li>
 *   <li>{@code <typeIndex>//<stringId>} - an item of another type with a string id.</li>
 * </ul>
 *
 * <p>The type index is a key into the {@code itemTypes} map the same response carries.
 *
 * <p>An example from that documentation, which is also the parser's test fixture:
 * {@code 10394:0:4/356,10332:0:14707,10374:1:5/240,10348:2:14717}.
 *
 * <p>Depths are absolute, not deltas, so {@link #toTree(List)} builds the hierarchy with a single pass and
 * a stack.
 */
public final class ForestFormula {

    /** A formula row is {@code rowId:depth:item}, with an optional fourth semantics field. */
    private static final int MINIMUM_ROW_PARTS = 3;

    private ForestFormula() {
    }

    /**
     * Parses a formula into rows, in document order.
     *
     * @param formula the {@code formula} string from a forest response; {@code null} or empty yields no rows
     * @return the rows, in the order Structure listed them
     * @throws JiraSerializationException when a row cannot be decoded
     */
    public static List<ForestRow> parse(String formula) {
        if (formula == null || formula.isBlank()) {
            return List.of();
        }
        List<ForestRow> rows = new ArrayList<>();
        for (String entry : formula.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                rows.add(parseRow(trimmed, formula));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Rebuilds the parent/child hierarchy the depths encode.
     *
     * @param rows rows in document order, as {@link #parse(String)} returns them
     * @return the top-level nodes, each carrying its children
     * @throws JiraSerializationException when a row's depth skips a level, which a well-formed forest never does
     */
    public static List<ForestNode> toTree(List<ForestRow> rows) {
        List<ForestNode> roots = new ArrayList<>();
        List<ForestNode> stack = new ArrayList<>();
        for (ForestRow row : rows) {
            ForestNode node = new ForestNode(row, new ArrayList<>());
            int depth = row.depth();
            if (depth > stack.size()) {
                throw new JiraSerializationException("Structure forest row " + row.rowId() + " is at depth "
                        + depth + " but its parent chain only reaches depth " + stack.size()
                        + "; the formula is not in document order", null);
            }
            while (stack.size() > depth) {
                stack.remove(stack.size() - 1);
            }
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.get(stack.size() - 1).children().add(node);
            }
            stack.add(node);
        }
        return List.copyOf(roots);
    }

    private static ForestRow parseRow(String entry, String formula) {
        String[] parts = entry.split(":");
        if (parts.length < MINIMUM_ROW_PARTS) {
            throw new JiraSerializationException("Structure forest row '" + entry
                    + "' does not have the documented rowId:depth:item shape; whole formula was: " + formula, null);
        }
        try {
            long rowId = Long.parseLong(parts[0]);
            int depth = Integer.parseInt(parts[1]);
            String identity = parts[2];
            String semantics = parts.length > MINIMUM_ROW_PARTS ? parts[MINIMUM_ROW_PARTS] : null;
            int slash = identity.indexOf('/');
            if (slash < 0) {
                return new ForestRow(rowId, depth, null, Long.parseLong(identity), null, semantics);
            }
            int typeIndex = Integer.parseInt(identity.substring(0, slash));
            String rest = identity.substring(slash + 1);
            if (rest.startsWith("/")) {
                return new ForestRow(rowId, depth, typeIndex, null, rest.substring(1), semantics);
            }
            return new ForestRow(rowId, depth, typeIndex, Long.parseLong(rest), null, semantics);
        } catch (NumberFormatException e) {
            throw new JiraSerializationException(
                    "Structure forest row '" + entry + "' carries a non-numeric id or depth", e);
        }
    }
}
