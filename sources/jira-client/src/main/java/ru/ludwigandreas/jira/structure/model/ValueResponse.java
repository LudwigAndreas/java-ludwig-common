package ru.ludwigandreas.jira.structure.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Structure's answer to a value request: one block per request block, each holding one value list per
 * attribute.
 *
 * <p>The values are positional. {@code data[j].values[k]} is attribute {@code j}'s value for
 * {@code rows[k]} - there are no row ids inside the value list, so the pairing is by index and nothing
 * else. {@link ValueBlock#valuesByRow(int)} does that zip so callers do not have to get it right by hand.
 *
 * @param responses one block per request block, in order
 * @param itemTypes item type table, as in a forest response
 * @param itemsVersion version of the item set the values were computed against
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ValueResponse(List<ValueBlock> responses, Map<String, String> itemTypes,
                            ForestVersion itemsVersion) {

    /** Normalizes the collections to immutable empty ones rather than {@code null}. */
    public ValueResponse {
        responses = responses == null ? List.of() : List.copyOf(responses);
        itemTypes = itemTypes == null ? Map.of() : Map.copyOf(itemTypes);
    }

    /** The first block, which is all there is for a single-block request. */
    public Optional<ValueBlock> first() {
        return responses.isEmpty() ? Optional.empty() : Optional.of(responses.get(0));
    }

    /**
     * One request block's values.
     *
     * @param forestSpec the forest the rows belong to
     * @param rows the row ids, in the order the values are given
     * @param data one entry per requested attribute
     * @param forestVersion version of the forest the values were computed against
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ValueBlock(ForestSpec forestSpec, List<Long> rows, List<AttributeValues> data,
                             ForestVersion forestVersion) {

        /** Normalizes the two collections to immutable empty lists rather than {@code null}. */
        public ValueBlock {
            rows = rows == null ? List.of() : List.copyOf(rows);
            data = data == null ? List.of() : List.copyOf(data);
        }

        /**
         * One attribute's values keyed by row id, zipping the positional lists.
         *
         * @param attributeIndex index into the attribute list that was requested
         * @return row id to value; rows with no value are absent
         */
        public Map<Long, JsonNode> valuesByRow(int attributeIndex) {
            if (attributeIndex < 0 || attributeIndex >= data.size()) {
                return Map.of();
            }
            List<JsonNode> values = data.get(attributeIndex).values();
            java.util.Map<Long, JsonNode> zipped = new java.util.LinkedHashMap<>();
            for (int i = 0; i < Math.min(rows.size(), values.size()); i++) {
                JsonNode value = values.get(i);
                if (value != null && !value.isNull()) {
                    zipped.put(rows.get(i), value);
                }
            }
            return java.util.Collections.unmodifiableMap(zipped);
        }
    }

    /**
     * The values of one attribute, in row order.
     *
     * @param attribute the attribute these are values of
     * @param values the values, positionally matching the block's row list
     * @param trailMode how Structure tracked the dependency trail for these values
     * @param trails the dependency trails, when requested
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AttributeValues(AttributeSpec attribute, List<JsonNode> values, String trailMode,
                                  List<JsonNode> trails) {

        /** Normalizes the two collections to immutable empty lists rather than {@code null}. */
        public AttributeValues {
            values = values == null ? List.of() : List.copyOf(values);
            trails = trails == null ? List.of() : List.copyOf(trails);
        }
    }
}
