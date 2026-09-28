package ru.ludwigandreas.export.enrich;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.export.api.CellValue;
import ru.ludwigandreas.export.api.Enricher;
import ru.ludwigandreas.export.api.EnrichmentStage;
import ru.ludwigandreas.export.api.FailurePolicy;
import ru.ludwigandreas.export.api.MissingPolicy;
import ru.ludwigandreas.export.exception.DimensionTooLargeException;
import ru.ludwigandreas.export.exception.EnrichmentFailedException;
import ru.ludwigandreas.export.exception.EnrichmentKeyMissingException;
import ru.ludwigandreas.export.i18n.ExportMessages;
import ru.ludwigandreas.export.metrics.ExportMetrics;

/**
 * The enrichment side of one run: the cache, the dimension catalogues, and what has degraded so far.
 *
 * <h2>Per window: collect, consult, call, join</h2>
 *
 * <p>Distinct keys are collected once per stage per window, the cache answers what it can, and only
 * what is left reaches a partner. The collection is what makes batching worth anything - a window of
 * two thousand rows referring to two hundred customers is one call, not two thousand - and the cache
 * is what makes the <em>next</em> window cheap.
 *
 * <h2>Concurrency within a level, merges on the caller's thread</h2>
 *
 * <p>Stages with no declared dependency form a level and their <em>fetches</em> run concurrently;
 * their <em>merges</em> are then applied one after another on the calling thread. That split is
 * deliberate. The fetches are what a report waits on, so they are worth parallelising; the merges
 * are pure functions over the window that take microseconds, and running them concurrently would
 * mean two stages producing two independently-derived versions of the same row and something having
 * to reconcile them. There is nothing to reconcile this way.
 *
 * <h2>A degraded stage is not called again</h2>
 *
 * <p>Once a stage has failed under {@code DEGRADE}, every later window marks its cells without
 * calling. The partner has already been given every retry its REST client allows; continuing to
 * call it for another five hundred windows would turn one partner's outage into this service's
 * outage, and would make the run take its wall-clock budget to produce a file that was going to
 * carry markers anyway.
 *
 * @param <R> the row type
 */
@Slf4j
public final class EnrichmentRun<R> {

    private final String definitionKey;
    private final List<List<EnrichmentStage<R, ?, ?>>> levels;
    private final EnrichmentCache cache;
    private final Executor executor;
    private final ExportMetrics metrics;
    private final ExportMessages messages;
    private final Locale locale;
    private final EnrichmentSettings settings;

    /** Nanoseconds in a millisecond, which is the unit the metrics are recorded in. */
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final Map<String, Map<Object, Object>> dimensions = new HashMap<>();
    private final Set<String> degraded = new LinkedHashSet<>();
    private final CellValue unavailable;

    // SUPPRESS CHECKSTYLE ParameterNumber - constructed in one place, by EnrichmentExecutor, which
    // is where the configuration is read; there is no positional call site for the rule to protect.
    @SuppressWarnings("checkstyle:ParameterNumber")
    EnrichmentRun(String definitionKey, List<EnrichmentStage<R, ?, ?>> stages, EnrichmentCache cache,
                  Executor executor, ExportMetrics metrics, ExportMessages messages, Locale locale,
                  EnrichmentSettings settings) {
        this.definitionKey = definitionKey;
        this.levels = StageOrdering.levels(stages);
        this.cache = cache;
        this.executor = executor;
        this.metrics = metrics;
        this.messages = messages;
        this.locale = locale;
        this.settings = settings;
        this.unavailable = new CellValue.Error("ludwig.export.cell.unavailable");
    }

    /** Stages that failed and were degraded rather than failing the run, in the order they failed. */
    public List<String> degradedStages() {
        return List.copyOf(degraded);
    }

    /** How many lookups this run answered from the cache, and how many it had to ask about. */
    public EnrichmentCache cache() {
        return cache;
    }

    /**
     * Enriches one window.
     *
     * @param window the rows, which are not modified
     * @return the surviving rows with their markers, and how many were dropped
     */
    public WindowEnrichment<R> enrich(List<R> window) {
        if (levels.isEmpty() || window.isEmpty()) {
            return new WindowEnrichment<>(window.stream().map(EnrichedRow::of).toList(), 0);
        }
        List<EnrichedRow<R>> rows = new ArrayList<>(window.size());
        window.forEach(row -> rows.add(EnrichedRow.of(row)));
        long dropped = 0;
        for (List<EnrichmentStage<R, ?, ?>> level : levels) {
            List<StageFetch<R>> fetches = fetchLevel(level, rows);
            for (StageFetch<R> fetch : fetches) {
                dropped += applyStage(fetch, rows);
            }
        }
        return new WindowEnrichment<>(rows, dropped);
    }

    /**
     * Runs every fetch of one level, concurrently.
     *
     * <p>{@code BoundedFanOut} is not used here: it bounds the calls <em>within</em> a stage, which
     * is what protects a partner, and a level's stages are by definition different partners. Two of
     * them in flight at once is the point.
     */
    private List<StageFetch<R>> fetchLevel(List<EnrichmentStage<R, ?, ?>> level,
                                           List<EnrichedRow<R>> rows) {
        if (level.size() == 1) {
            return List.of(fetchStage(level.get(0), rows));
        }
        List<CompletableFuture<StageFetch<R>>> futures = new ArrayList<>();
        for (EnrichmentStage<R, ?, ?> stage : level) {
            futures.add(executor == null
                    ? CompletableFuture.completedFuture(fetchStage(stage, rows))
                    : CompletableFuture.supplyAsync(
                            () -> fetchStage(stage, rows), executor));
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    @SuppressWarnings("unchecked")
    private StageFetch<R> fetchStage(EnrichmentStage<R, ?, ?> stage, List<EnrichedRow<R>> rows) {
        return fetchTyped((EnrichmentStage<R, Object, Object>) stage, rows);
    }

    private StageFetch<R> fetchTyped(EnrichmentStage<R, Object, Object> stage,
                                     List<EnrichedRow<R>> rows) {
        if (degraded.contains(stage.getName())) {
            return StageFetch.degraded(stage);
        }
        Set<Object> keys = new LinkedHashSet<>();
        for (EnrichedRow<R> row : rows) {
            Object key = stage.getKeyExtractor().apply(row.row());
            if (key != null) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            return StageFetch.resolved(stage, Map.of(), Set.of());
        }
        EnrichmentCache.Lookup<Object, Object> lookup = settings.cacheEnabled(stage)
                ? cache.lookup(stage.getName(), keys)
                : new EnrichmentCache.Lookup<>(Map.of(), List.copyOf(keys), List.of());
        metrics.enrichmentCacheAccess(definitionKey, stage.getName(), lookup.hitCount(),
                lookup.unresolved().size());
        if (lookup.unresolved().isEmpty()) {
            return StageFetch.resolved(stage, lookup.resolved(), Set.copyOf(lookup.absent()));
        }
        try {
            Map<Object, Object> fetched = call(stage, lookup.unresolved());
            if (settings.cacheEnabled(stage)) {
                cache.store(stage.getName(), lookup.unresolved(), fetched);
            }
            Map<Object, Object> resolved = new HashMap<>(lookup.resolved());
            resolved.putAll(fetched);
            Set<Object> absent = new LinkedHashSet<>(lookup.absent());
            lookup.unresolved().stream().filter(key -> !fetched.containsKey(key)).forEach(absent::add);
            metrics.enrichmentKeysMissing(definitionKey, stage.getName(), absent.size());
            return StageFetch.resolved(stage, resolved, absent);
        } catch (RuntimeException e) {
            return onFetchFailure(stage, e);
        }
    }

    private StageFetch<R> onFetchFailure(EnrichmentStage<R, Object, Object> stage, RuntimeException e) {
        if (e instanceof DimensionTooLargeException) {
            // Not a partner failure, so not something FailurePolicy gets to decide about. The
            // partner answered; the stage declared a ceiling its catalogue does not fit under, which
            // is a mistake in the definition. Letting DEGRADE swallow it would ship a file full of
            // "unavailable" markers whose real cause is a number somebody has to change.
            throw e;
        }
        if (stage.getFailurePolicy() == FailurePolicy.FAIL_REPORT) {
            throw new EnrichmentFailedException(stage.getName(), e);
        }
        if (degraded.add(stage.getName())) {
            metrics.stageDegraded(definitionKey, stage.getName());
            log.warn("Enrichment stage '{}' of report {} failed and is degraded for the rest of the"
                            + " run; its cells will carry a marker and it will not be called again",
                    stage.getName(), definitionKey, e);
        }
        return StageFetch.degraded(stage);
    }

    private Map<Object, Object> call(EnrichmentStage<R, Object, Object> stage, List<Object> keys) {
        long started = System.nanoTime();
        try {
            Map<Object, Object> result = dispatch(stage, keys);
            record(stage, "success", started);
            return result;
        } catch (RuntimeException e) {
            record(stage, "failure", started);
            throw e;
        }
    }

    private Map<Object, Object> dispatch(EnrichmentStage<R, Object, Object> stage, List<Object> keys) {
        Enricher<Object, Object> enricher = stage.getEnricher();
        if (enricher instanceof Enricher.Dimension<Object, Object> dimension) {
            return fromDimension(stage, dimension, keys);
        }
        if (enricher instanceof Enricher.Batched<Object, Object> batched) {
            return fromBatches(stage, batched, keys);
        }
        return fromItems(stage, (Enricher.PerItem<Object, Object>) enricher, keys);
    }

    private Map<Object, Object> fromBatches(EnrichmentStage<R, Object, Object> stage,
                                            Enricher.Batched<Object, Object> batched,
                                            List<Object> keys) {
        List<List<Object>> chunks = chunk(keys, settings.batchSize(stage));
        List<Map<Object, Object>> answers = BoundedFanOut.run(executor,
                settings.concurrency(stage), chunks, batched::fetchBatch);
        Map<Object, Object> merged = new HashMap<>();
        answers.forEach(merged::putAll);
        return merged;
    }

    private Map<Object, Object> fromItems(EnrichmentStage<R, Object, Object> stage,
                                          Enricher.PerItem<Object, Object> perItem,
                                          List<Object> keys) {
        List<Optional<Object>> answers = BoundedFanOut.run(executor,
                settings.concurrency(stage), keys, perItem::fetchOne);
        Map<Object, Object> merged = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            Object value = answers.get(i).orElse(null);
            if (value != null) {
                merged.put(keys.get(i), value);
            }
        }
        return merged;
    }

    /**
     * Resolves keys against a catalogue fetched once for the whole run.
     *
     * <p>Fetched lazily, on the first window that needs it, rather than eagerly at run start: a
     * report whose filter happens to select no rows should not call a partner at all, and a run that
     * fails on its first window should not have paid for a catalogue it never used.
     */
    private Map<Object, Object> fromDimension(EnrichmentStage<R, Object, Object> stage,
                                              Enricher.Dimension<Object, Object> dimension,
                                              List<Object> keys) {
        Map<Object, Object> catalogue = dimensions.computeIfAbsent(stage.getName(), name -> {
            Map<Object, Object> fetched = dimension.fetchAll();
            int limit = settings.maxDimensionEntries(stage);
            if (fetched.size() > limit) {
                throw new DimensionTooLargeException(name, limit, fetched.size());
            }
            log.debug("Enrichment stage '{}' of report {} loaded a catalogue of {} entries",
                    name, definitionKey, fetched.size());
            return Map.copyOf(fetched);
        });
        Map<Object, Object> found = new HashMap<>();
        for (Object key : keys) {
            Object value = catalogue.get(key);
            if (value != null) {
                found.put(key, value);
            }
        }
        return found;
    }

    /**
     * Applies one stage's answers to the window.
     *
     * @return how many rows this stage removed
     */
    private long applyStage(StageFetch<R> fetch, List<EnrichedRow<R>> rows) {
        EnrichmentStage<R, Object, Object> stage = fetch.stage();
        MissingPolicy policy = stage.getMissingPolicy();
        CellValue placeholder = policy.kind() == MissingPolicy.Kind.PLACEHOLDER
                ? new CellValue.Error(policy.messageKey())
                : null;
        long dropped = 0;
        for (int i = 0; i < rows.size(); i++) {
            EnrichedRow<R> current = rows.get(i);
            if (current == null) {
                continue;
            }
            if (fetch.isDegraded()) {
                rows.set(i, current.marked(stage.getName(), unavailable));
                continue;
            }
            Object key = stage.getKeyExtractor().apply(current.row());
            if (key == null) {
                // No key means this row has nothing to look up, which is not the same as the partner
                // not knowing it. The stage's columns then simply have no value, and the column's own
                // NullPolicy decides what that looks like.
                continue;
            }
            Object value = fetch.values().get(key);
            if (value != null) {
                rows.set(i, current.withRow(stage.getMerge().apply(current.row(), value)));
                continue;
            }
            if (policy.kind() == MissingPolicy.Kind.FAIL_REPORT) {
                throw new EnrichmentKeyMissingException(stage.getName());
            }
            if (policy.kind() == MissingPolicy.Kind.FAIL_ROW) {
                rows.set(i, null);
                dropped++;
                continue;
            }
            rows.set(i, current.marked(stage.getName(), placeholder));
        }
        if (dropped > 0) {
            metrics.rowsDropped(definitionKey, stage.getName(), dropped);
            rows.removeIf(Objects::isNull);
        }
        return dropped;
    }

    private void record(EnrichmentStage<R, Object, Object> stage, String outcome, long startedNanos) {
        long millis = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI;
        metrics.enrichmentCall(definitionKey, stage.getName(), outcome, millis);
    }

    private static <T> List<List<T>> chunk(List<T> items, int size) {
        List<List<T>> chunks = new ArrayList<>((items.size() / size) + 1);
        for (int start = 0; start < items.size(); start += size) {
            chunks.add(items.subList(start, Math.min(items.size(), start + size)));
        }
        return chunks;
    }
}
