package ru.ludwigandreas.export.enrich;

import java.util.Map;
import ru.ludwigandreas.export.api.CellValue;

/**
 * One row after the stages have had their turn, and what could not be filled in.
 *
 * <p>The markers are keyed by stage name and are almost always empty, which is why they are a map
 * rather than a field per stage: at the design point this record is created a million times, and
 * {@code Map.of()} allocates nothing. A column whose {@code requiredStage} appears here is written
 * as the marker instead of being extracted, which is how a partner's gap reaches the file as
 * something a reader can see rather than as a blank cell.
 *
 * @param row     the row, with every stage that succeeded already merged in
 * @param markers stage name to the cell its columns get instead of a value
 * @param <R>     the row type
 */
public record EnrichedRow<R>(R row, Map<String, CellValue> markers) {

    /** A row nothing went wrong for. */
    public static <R> EnrichedRow<R> of(R row) {
        return new EnrichedRow<>(row, Map.of());
    }

    /** The same row with one more stage marked. */
    public EnrichedRow<R> marked(String stageName, CellValue marker) {
        Map<String, CellValue> merged = new java.util.LinkedHashMap<>(markers);
        merged.put(stageName, marker);
        return new EnrichedRow<>(row, Map.copyOf(merged));
    }

    /** The same row, enriched by a stage. */
    public EnrichedRow<R> withRow(R enriched) {
        return new EnrichedRow<>(enriched, markers);
    }
}
