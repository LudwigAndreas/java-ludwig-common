package ru.ludwigandreas.jira.structure.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * What Structure reports after a forest update.
 *
 * <p>{@code oldRowIds}/{@code newRowIds} are the interesting part of an {@code add}: rows are inserted with
 * caller-chosen negative placeholder ids, and Structure answers with the real ids it assigned, positionally
 * paired with the placeholders. Anything that adds rows and then needs to address them has to read that
 * mapping - the placeholder is not a handle after the call returns.
 *
 * @param successfulActions how many of the submitted actions were applied
 * @param oldRowIds the placeholder ids sent, in order
 * @param newRowIds the ids Structure assigned, positionally matching {@code oldRowIds}
 * @param forestUpdates the forest changes since the version quoted in the request
 * @param itemsUpdate changes to the item set since that version
 * @param version the forest's version after the update
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ForestUpdateResult(Integer successfulActions,
                                 List<Long> oldRowIds,
                                 List<Long> newRowIds,
                                 JsonNode forestUpdates,
                                 JsonNode itemsUpdate,
                                 ForestVersion version) {

    /** Normalizes the two id lists to immutable empty lists rather than {@code null}. */
    public ForestUpdateResult {
        oldRowIds = oldRowIds == null ? List.of() : List.copyOf(oldRowIds);
        newRowIds = newRowIds == null ? List.of() : List.copyOf(newRowIds);
    }

    /**
     * The placeholder-to-real row id mapping, as a map.
     *
     * <p>Empty when the update added no rows. Pairs are matched by position, which is how Structure sends
     * them; a response whose two lists differ in length is truncated and yields only the pairs that line up.
     *
     * @return placeholder row id to assigned row id
     */
    public Map<Long, Long> assignedRowIds() {
        int pairs = Math.min(oldRowIds.size(), newRowIds.size());
        return java.util.stream.IntStream.range(0, pairs)
                .boxed()
                .collect(java.util.stream.Collectors.toMap(oldRowIds::get, newRowIds::get, (a, b) -> b,
                        java.util.LinkedHashMap::new));
    }
}
