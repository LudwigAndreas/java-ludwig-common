package ru.ludwigandreas.reconciliation.unit;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.reconciliation.api.DemandProvider;
import ru.ludwigandreas.reconciliation.api.DemandRequest;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.api.FetchOutcome;
import ru.ludwigandreas.reconciliation.api.Fetcher;
import ru.ludwigandreas.reconciliation.api.KeyCodec;
import ru.ludwigandreas.reconciliation.api.PageResult;
import ru.ludwigandreas.reconciliation.api.ReconcileResult;
import ru.ludwigandreas.reconciliation.api.Reconciler;
import ru.ludwigandreas.reconciliation.api.SyncTask;
import ru.ludwigandreas.reconciliation.config.FetchShape;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.config.TaskSettingsResolver;
import ru.ludwigandreas.reconciliation.engine.FetchWalker;
import ru.ludwigandreas.reconciliation.engine.RegisteredTask;
import ru.ludwigandreas.reconciliation.engine.RunContext;
import ru.ludwigandreas.reconciliation.engine.TaskStateService;
import ru.ludwigandreas.reconciliation.metrics.NoopReconciliationMetrics;
import ru.ludwigandreas.reconciliation.quota.RateLimitRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FetchWalkerTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final TaskStateService taskState = mock(TaskStateService.class);
    private final FetchWalker walker = new FetchWalker(executor, new NoopReconciliationMetrics(),
            new RateLimitRegistry(Map.of()), taskState);

    private final List<FetchOutcome<String, String>> collected = new ArrayList<>();

    @Test
    void perItemProducesFoundForEveryKeyThePartnerKnows() {
        var task = task(FetchShape.PER_ITEM,
                (Fetcher.PerItem<String, String>) key -> Optional.of("record-" + key), settings -> { });

        walker.walk(task, keys("a", "b"), context(), this::collect);

        assertThat(collected).containsExactlyInAnyOrder(
                FetchOutcome.found("a", "record-a"), FetchOutcome.found("b", "record-b"));
    }

    /**
     * An empty answer is a NOT FOUND, not a failure. Getting this wrong is the single most common
     * cause of runaway retry loops in this class of system.
     */
    @Test
    void perItemProducesNotFoundForAKeyThePartnerDoesNotKnow() {
        var task = task(FetchShape.PER_ITEM,
                (Fetcher.PerItem<String, String>) key -> Optional.empty(), settings -> { });

        walker.walk(task, keys("a"), context(), this::collect);

        assertThat(collected).containsExactly(FetchOutcome.notFound("a"));
    }

    @Test
    void perItemTurnsAThrownExceptionIntoAFailureForThatKeyOnly() {
        var task = task(FetchShape.PER_ITEM, (Fetcher.PerItem<String, String>) key -> {
            if ("bad".equals(key)) {
                throw new IllegalStateException("partner exploded");
            }
            return Optional.of("record-" + key);
        }, settings -> { });

        walker.walk(task, keys("good", "bad"), context(), this::collect);

        assertThat(collected).hasSize(2);
        assertThat(collected).anySatisfy(outcome ->
                assertThat(outcome).isInstanceOf(FetchOutcome.Found.class));
        assertThat(collected).anySatisfy(outcome -> assertThat(outcome)
                .isInstanceOfSatisfying(FetchOutcome.Failed.class, failure ->
                        assertThat(failure.reason()).contains("partner exploded")));
    }

    @Test
    void perItemRespectsItsConcurrencyBound() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        var task = task(FetchShape.PER_ITEM, (Fetcher.PerItem<String, String>) key -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return Optional.of(key);
        }, settings -> settings.getFetch().setMaxConcurrency(2));

        walker.walk(task, keys(IntStream.range(0, 12).mapToObj(String::valueOf).toArray(String[]::new)),
                context(), this::collect);

        assertThat(peak.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void batchedSlicesDemandIntoChunksOfTheConfiguredSize() {
        List<Integer> chunkSizes = java.util.Collections.synchronizedList(new ArrayList<>());
        var task = task(FetchShape.BATCHED, (Fetcher.Batched<String, String>) batch -> {
            chunkSizes.add(batch.size());
            return batch.stream().collect(Collectors.toMap(Function.identity(), key -> "record-" + key));
        }, settings -> settings.getFetch().setBatchSize(3));

        walker.walk(task, keys(IntStream.range(0, 7).mapToObj(String::valueOf).toArray(String[]::new)),
                context(), this::collect);

        assertThat(chunkSizes).containsExactlyInAnyOrder(3, 3, 1);
        assertThat(collected).hasSize(7);
    }

    /**
     * The mapping that exists once here rather than in every batched fetcher: a key the partner left
     * out of its answer is NOT FOUND, never a missing result to retry.
     */
    @Test
    void batchedTreatsAnAbsentKeyAsNotFoundRatherThanAsAnError() {
        var task = task(FetchShape.BATCHED,
                (Fetcher.Batched<String, String>) batch -> Map.of("a", "record-a"),
                settings -> settings.getFetch().setBatchSize(10));

        walker.walk(task, keys("a", "b"), context(), this::collect);

        assertThat(collected).containsExactlyInAnyOrder(
                FetchOutcome.found("a", "record-a"), FetchOutcome.notFound("b"));
    }

    @Test
    void batchedFailsOnlyTheChunkThatThrew() {
        var task = task(FetchShape.BATCHED, (Fetcher.Batched<String, String>) batch -> {
            if (batch.contains("2")) {
                throw new IllegalStateException("chunk exploded");
            }
            return batch.stream().collect(Collectors.toMap(Function.identity(), key -> "r" + key));
        }, settings -> settings.getFetch().setBatchSize(2));

        walker.walk(task, keys("0", "1", "2", "3"), context(), this::collect);

        assertThat(collected).hasSize(4);
        assertThat(collected.stream().filter(FetchOutcome.Failed.class::isInstance)).hasSize(2);
        assertThat(collected.stream().filter(FetchOutcome.Found.class::isInstance)).hasSize(2);
    }

    @Test
    void pagedWalksEveryPageAndJoinsTheRecordsAgainstDemand() {
        var task = task(FetchShape.PAGED, pagedFetcher(
                PageResult.of(List.of("a", "b"), "cursor-1"),
                PageResult.last(List.of("c"))), settings -> { });
        when(taskState.cursor(anyString(), any())).thenReturn(Optional.empty());
        when(taskState.watermark(anyString(), any())).thenReturn(Optional.empty());

        walker.walk(task, keys("a", "c"), context(), this::collect);

        assertThat(collected).containsExactly(
                FetchOutcome.found("a", "a"), FetchOutcome.found("c", "c"));
    }

    @Test
    void pagedWithNoDemandTakesEverythingInTheCatalogue() {
        var task = task(FetchShape.PAGED, pagedFetcher(PageResult.last(List.of("a", "b", "c"))),
                settings -> { });
        when(taskState.cursor(anyString(), any())).thenReturn(Optional.empty());
        when(taskState.watermark(anyString(), any())).thenReturn(Optional.empty());

        walker.walk(task, Set.of(), context(), this::collect);

        assertThat(collected).hasSize(3);
    }

    @Test
    void pagedResumesFromThePersistedCursorRatherThanFromPageZero() {
        List<String> requestedCursors = new ArrayList<>();
        var task = task(FetchShape.PAGED, new Fetcher.Paged<String, String>() {
            @Override
            public PageResult<String> fetchPage(String cursor,
                                                ru.ludwigandreas.reconciliation.api.PageRequest request) {
                requestedCursors.add(cursor);
                return PageResult.last(List.of("a"));
            }

            @Override
            public String keyOf(String record) {
                return record;
            }
        }, settings -> { });
        when(taskState.cursor(anyString(), any())).thenReturn(Optional.of("page-40"));
        when(taskState.watermark(anyString(), any())).thenReturn(Optional.empty());

        walker.walk(task, Set.of(), context(), this::collect);

        assertThat(requestedCursors).containsExactly("page-40");
    }

    private int collect(List<FetchOutcome<String, String>> outcomes) {
        collected.addAll(outcomes);
        return outcomes.size();
    }

    private static Fetcher.Paged<String, String> pagedFetcher(PageResult<String>... pages) {
        AtomicInteger index = new AtomicInteger();
        return new Fetcher.Paged<>() {
            @Override
            public PageResult<String> fetchPage(String cursor,
                                                ru.ludwigandreas.reconciliation.api.PageRequest request) {
                return pages[Math.min(index.getAndIncrement(), pages.length - 1)];
            }

            @Override
            public String keyOf(String record) {
                return record;
            }
        };
    }

    private static Set<String> keys(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    private static RunContext context() {
        return new RunContext("t", DemandTier.HOT, UUID.randomUUID(), "corr",
                Instant.now(), Instant.now().plusSeconds(60));
    }

    private static RegisteredTask<String, String, String> task(
            FetchShape shape,
            Fetcher<String, String> fetcher,
            java.util.function.Consumer<ReconciliationProperties.Task> customize) {
        ReconciliationProperties properties = new ReconciliationProperties();
        ReconciliationProperties.Task configured = new ReconciliationProperties.Task();
        configured.getFetch().setShape(shape);
        customize.accept(configured);
        properties.getTasks().put("t", configured);
        TaskSettings settings = TaskSettingsResolver.resolve("t", configured, properties);
        return new RegisteredTask<>(new StubTask(fetcher), settings);
    }

    /** A task whose only interesting part is its fetcher. */
    private record StubTask(Fetcher<String, String> fetcher) implements SyncTask<String, String, String> {

        @Override
        public String name() {
            return "t";
        }

        @Override
        public DemandProvider<String, String> demand() {
            return new DemandProvider<>() {
                @Override
                public List<String> demand(DemandRequest request) {
                    return List.of();
                }

                @Override
                public Optional<String> byKey(String key) {
                    return Optional.of(key);
                }
            };
        }

        @Override
        public Function<String, String> localKey() {
            return Function.identity();
        }

        @Override
        public KeyCodec<String> keyCodec() {
            return KeyCodec.ofString();
        }

        @Override
        public Reconciler<String, String> reconciler() {
            return (local, external, context) -> ReconcileResult.unchanged();
        }

        @Override
        public Class<String> externalType() {
            return String.class;
        }
    }
}
