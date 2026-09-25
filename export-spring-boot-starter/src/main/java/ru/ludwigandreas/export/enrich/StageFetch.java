package ru.ludwigandreas.export.enrich;

import java.util.Map;
import java.util.Set;
import ru.ludwigandreas.export.api.EnrichmentStage;

/**
 * What one stage found for one window, before any of it has been merged in.
 *
 * <p>Fetching and merging are separate steps because they run on different threads: a level's
 * fetches go out concurrently and the merges are applied one after another on the calling thread.
 * This record is what crosses between them, which is why it carries no rows - only answers, keyed
 * by key.
 *
 * <p>The degraded flag is a shape of its own rather than an empty result. "The partner said it knows
 * none of these keys" and "the partner did not answer" lead to completely different cells, and a
 * writer that could not tell them apart would print the not-found marker for an outage.
 *
 * @param stage    the stage these answers belong to
 * @param values   what the partner knew, keyed by key
 * @param absent   keys it answered about and did not know
 * @param degraded whether the stage failed and is being degraded
 * @param <R>      the row type
 */
record StageFetch<R>(EnrichmentStage<R, Object, Object> stage, Map<Object, Object> values,
                     Set<Object> absent, boolean degraded) {

    static <R> StageFetch<R> resolved(EnrichmentStage<R, Object, Object> stage,
                                      Map<Object, Object> values, Set<Object> absent) {
        return new StageFetch<>(stage, values, absent, false);
    }

    static <R> StageFetch<R> degraded(EnrichmentStage<R, Object, Object> stage) {
        return new StageFetch<>(stage, Map.of(), Set.of(), true);
    }

    boolean isDegraded() {
        return degraded;
    }
}
