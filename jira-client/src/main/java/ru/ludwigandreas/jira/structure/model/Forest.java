package ru.ludwigandreas.jira.structure.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * A forest as Structure returns it: the spec that produced it, the rows encoded as a formula, the item-type
 * table the formula's indexes refer to, and the version to quote on an update.
 *
 * @param spec the specification this content was produced from
 * @param formula the rows, encoded - decode with {@link #rows()}
 * @param itemTypes maps the formula's numeric type indexes to Structure item type keys
 * @param version the optimistic-concurrency token to send back on an update
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Forest(ForestSpec spec, String formula, Map<String, String> itemTypes, ForestVersion version) {

    /** Normalizes {@code itemTypes} to an immutable empty map rather than {@code null}. */
    public Forest {
        itemTypes = itemTypes == null ? Map.of() : Map.copyOf(itemTypes);
    }

    /** The rows, decoded from the formula, in document order. */
    public List<ForestRow> rows() {
        return ForestFormula.parse(formula);
    }

    /** The rows as a hierarchy. */
    public List<ForestNode> tree() {
        return ForestFormula.toTree(rows());
    }

    /** The Structure item type key a row's type index refers to, or {@code null} for an issue row. */
    public String itemTypeOf(ForestRow row) {
        return row.itemTypeIndex() == null ? null : itemTypes.get(String.valueOf(row.itemTypeIndex()));
    }
}
