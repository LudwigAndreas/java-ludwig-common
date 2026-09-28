package ru.ludwigandreas.reconciliation.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.reconciliation.api.FetchOutcome;
import ru.ludwigandreas.reconciliation.api.Fetcher;
import ru.ludwigandreas.reconciliation.api.PageRequest;
import ru.ludwigandreas.reconciliation.api.PageResult;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.exception.ReconciliationException;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.quota.RateLimitRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Reduces the three synchronous fetch shapes to one stream of {@link FetchOutcome}s.
 *
 * <p>The asynchronous-job shape is not here on purpose: it does not fit inside one run. It spans
 * scheduler ticks and process restarts, holds a partner-side resource while it does, and is therefore
 * driven by its own three schedulers - which then feed their collected pages back through the same
 * paged walk this class implements.
 */
public class FetchWalker {

    private static final Logger log = LoggerFactory.getLogger(FetchWalker.class);

    private final Executor executor;
    private final ReconciliationMetrics metrics;
    private final RateLimitRegistry rateLimits;
    private final TaskStateService taskState;

    /**
     * Creates the walker.
     *
     * @param executor   the shared fetch executor
     * @param metrics    instrumentation
     * @param rateLimits partner-scoped request-rate budgets
     * @param taskState  cursor checkpointing for the paged shape
     */
    public FetchWalker(Executor executor,
                       ReconciliationMetrics metrics,
                       RateLimitRegistry rateLimits,
                       TaskStateService taskState) {
        this.executor = executor;
        this.metrics = metrics;
        this.rateLimits = rateLimits;
        this.taskState = taskState;
    }

    /**
     * Walks a task's fetcher over {@code keys}, handing outcomes to {@code sink} as they arrive.
     *
     * @param <I>     local record type
     * @param <K>     correlation key type
     * @param <O>     external record type
     * @param task    the task
     * @param keys    the demand, already filtered of suppressed keys
     * @param context the run
     * @param sink    where outcomes go
     * @return how many rows the sink produced
     */
    public <I, K, O> int walk(RegisteredTask<I, K, O> task,
                              Set<K> keys,
                              RunContext context,
                              FetchSink<K, O> sink) {
        Fetcher<K, O> fetcher = task.task().fetcher();
        if (fetcher instanceof Fetcher.PerItem<K, O> perItem) {
            return walkPerItem(task, perItem, keys, context, sink);
        }
        if (fetcher instanceof Fetcher.Batched<K, O> batched) {
            return walkBatched(task, batched, keys, context, sink);
        }
        if (fetcher instanceof Fetcher.Paged<K, O> paged) {
            return walkPaged(task, paged, keys, context, sink);
        }
        throw new ReconciliationException("Task '" + task.name() + "' uses the async-job shape, which is "
                + "driven by its own submit/poll/collect schedulers and never walked inside a run");
    }

    private <I, K, O> int walkPerItem(RegisteredTask<I, K, O> task,
                                      Fetcher.PerItem<K, O> fetcher,
                                      Set<K> keys,
                                      RunContext context,
                                      FetchSink<K, O> sink) {
        TaskSettings settings = task.settings();
        List<K> ordered = List.copyOf(keys);
        List<FetchOutcome<K, O>> outcomes = BoundedFanOut.run(executor,
                settings.fetch().maxConcurrency(), ordered,
                key -> timed(settings, () -> fetcher.fetchOne(key)
                        .<FetchOutcome<K, O>>map(record -> FetchOutcome.found(key, record))
                        .orElseGet(() -> FetchOutcome.notFound(key))),
                (key, error) -> FetchOutcome.failed(key, describe(error)));
        return sink.accept(outcomes);
    }

    /**
     * Slices demand into chunks and fans the chunks out.
     *
     * <p>A key the partner left out of its answer is a NOT FOUND, not a missing result to be retried.
     * That mapping happens here, once, rather than being left to every batched fetcher to remember -
     * getting it wrong is what turns a handful of forgotten ids into a permanent retry loop.
     */
    private <I, K, O> int walkBatched(RegisteredTask<I, K, O> task,
                                      Fetcher.Batched<K, O> fetcher,
                                      Set<K> keys,
                                      RunContext context,
                                      FetchSink<K, O> sink) {
        TaskSettings settings = task.settings();
        List<List<K>> chunks = chunk(List.copyOf(keys), settings.fetch().batchSize());
        List<List<FetchOutcome<K, O>>> results = BoundedFanOut.run(executor,
                settings.fetch().maxConcurrency(), chunks,
                chunkKeys -> timed(settings, () -> toOutcomes(chunkKeys, fetcher.fetchBatch(chunkKeys))),
                (chunkKeys, error) -> chunkKeys.stream()
                        .<FetchOutcome<K, O>>map(key -> FetchOutcome.failed(key, describe(error)))
                        .toList());
        int staged = 0;
        for (List<FetchOutcome<K, O>> result : results) {
            staged += sink.accept(result);
        }
        return staged;
    }

    /**
     * Walks pages, checkpointing the cursor after each one, joining the records against demand.
     *
     * <p>The join is what makes a catalogue pull addressable: the partner sends records, not answers
     * to questions, so a record whose key is not in this run's demand is simply not this run's
     * business. An empty demand set means "take everything", which is what a cold full sweep wants.
     *
     * <p>The cursor is written <em>after</em> the page it describes has been staged, never before. The
     * other order loses a page on any crash between the two writes, and loses it silently - the sweep
     * resumes past records it never wrote down.
     */
    private <I, K, O> int walkPaged(RegisteredTask<I, K, O> task,
                                    Fetcher.Paged<K, O> fetcher,
                                    Set<K> keys,
                                    RunContext context,
                                    FetchSink<K, O> sink) {
        TaskSettings settings = task.settings();
        boolean checkpoint = settings.fetch().checkpoint();
        String cursor = checkpoint
                ? taskState.cursor(settings.name(), context.tier()).orElse(null)
                : null;
        Instant watermark = settings.demand().incremental()
                ? taskState.watermark(settings.name(), context.tier()).orElse(null)
                : null;

        int staged = 0;
        int seen = 0;
        boolean more = true;
        while (more) {
            if (context.isExpired()) {
                log.info("Task '{}' stopped its paged sweep at the run timeout; the cursor is "
                        + "checkpointed and the next run resumes from it", settings.name());
                break;
            }
            // Effectively final for the lambda below; the loop variable moves on afterwards.
            String pageCursor = cursor;
            PageResult<O> page = timed(settings, () -> fetcher.fetchPage(pageCursor, new PageRequest(
                    settings.fetch().pageSize(), watermark, context.runId())));

            List<FetchOutcome<K, O>> outcomes = new ArrayList<>(page.items().size());
            for (O record : page.items()) {
                K key = fetcher.keyOf(record);
                if (keys.isEmpty() || keys.contains(key)) {
                    outcomes.add(FetchOutcome.found(key, record));
                }
            }
            staged += sink.accept(outcomes);
            seen += page.items().size();

            cursor = page.nextCursor();
            more = page.hasMore() && cursor != null;
            if (checkpoint) {
                taskState.checkpoint(settings.name(), context.tier(), more ? cursor : null);
            }
            if (seen >= settings.demand().maxRecordsPerRun()) {
                log.debug("Task '{}' reached its per-run record cap at {} records; the sweep resumes "
                        + "from the checkpoint next run", settings.name(), seen);
                break;
            }
        }
        return staged;
    }

    private <K, O> List<FetchOutcome<K, O>> toOutcomes(Collection<K> keys, Map<K, O> found) {
        List<FetchOutcome<K, O>> outcomes = new ArrayList<>(keys.size());
        for (K key : keys) {
            O record = found == null ? null : found.get(key);
            outcomes.add(record == null ? FetchOutcome.notFound(key) : FetchOutcome.found(key, record));
        }
        return outcomes;
    }

    private <T> T timed(TaskSettings settings, java.util.function.Supplier<T> call) {
        rateLimits.acquire(settings);
        Instant started = Instant.now();
        try {
            return call.get();
        } finally {
            metrics.recordFetchDuration(settings.name(),
                    settings.fetch().shape().name().toLowerCase(Locale.ROOT),
                    Duration.between(started, Instant.now()));
        }
    }

    private static <T> List<List<T>> chunk(List<T> items, int size) {
        List<List<T>> chunks = new ArrayList<>((items.size() / size) + 1);
        for (int start = 0; start < items.size(); start += size) {
            chunks.add(items.subList(start, Math.min(items.size(), start + size)));
        }
        return chunks;
    }

    /** Keeps demand order stable so a capped run always takes the same first N keys. */
    static <K> Set<K> ordered(Collection<K> keys) {
        return new LinkedHashSet<>(keys);
    }

    private static String describe(Throwable error) {
        return Optional.ofNullable(error)
                .map(e -> e.getClass().getSimpleName() + ": " + e.getMessage())
                .orElse("unknown failure");
    }
}
