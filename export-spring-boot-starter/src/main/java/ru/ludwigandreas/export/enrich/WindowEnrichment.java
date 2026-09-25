package ru.ludwigandreas.export.enrich;

import java.util.List;

/**
 * What one window looks like after enrichment.
 *
 * <p>The dropped count is carried rather than inferred from the size difference, because the two
 * are not the same question: the engine needs to know how many rows a stage's
 * {@code MissingPolicy.failRow()} removed in order to report it, and comparing sizes would conflate
 * that with a window that was simply short.
 *
 * @param rows    the surviving rows, with their markers
 * @param dropped how many rows a stage's missing policy removed from this window
 * @param <R>     the row type
 */
public record WindowEnrichment<R>(List<EnrichedRow<R>> rows, long dropped) {

    public WindowEnrichment {
        rows = List.copyOf(rows);
    }
}
