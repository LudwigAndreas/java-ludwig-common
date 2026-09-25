package ru.ludwigandreas.export.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.export.api.CellValue;
import ru.ludwigandreas.export.api.Enricher;
import ru.ludwigandreas.export.api.EnrichmentStage;
import ru.ludwigandreas.export.api.FailurePolicy;
import ru.ludwigandreas.export.api.MissingPolicy;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.enrich.EnrichedRow;
import ru.ludwigandreas.export.enrich.EnrichmentExecutor;
import ru.ludwigandreas.export.enrich.EnrichmentRun;
import ru.ludwigandreas.export.enrich.WindowEnrichment;
import ru.ludwigandreas.export.exception.DimensionTooLargeException;
import ru.ludwigandreas.export.exception.EnrichmentFailedException;
import ru.ludwigandreas.export.exception.EnrichmentKeyMissingException;
import ru.ludwigandreas.export.exception.ExportConfigurationException;
import ru.ludwigandreas.export.metrics.NoopExportMetrics;

/**
 * The three enrichment shapes, the cache, and what each policy actually does to a window.
 *
 * <p>Driven with in-process enrichers rather than a stubbed HTTP partner, because these are
 * questions about the engine's own decisions - how many calls it makes, which cells it marks, which
 * rows it drops. What a real partner behind {@code rest-client} does to those decisions is an
 * integration concern and belongs with the integration tests.
 */
class EnrichmentTest {

    private record Order(String id, String customerId, String customerName) {

        Order withCustomer(String name) {
            return new Order(id, customerId, name);
        }
    }

    /** A partner that records what it was asked, so a test can assert on the call pattern. */
    private static final class RecordingPartner implements Enricher.Batched<Object, Object> {

        private final Map<Object, Object> known;
        private final List<Integer> callSizes = new ArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();

        RecordingPartner(Map<Object, Object> known) {
            this.known = known;
        }

        @Override
        public Map<Object, Object> fetchBatch(java.util.Collection<Object> keys) {
            calls.incrementAndGet();
            synchronized (callSizes) {
                callSizes.add(keys.size());
            }
            Map<Object, Object> found = new LinkedHashMap<>();
            keys.forEach(key -> {
                Object value = known.get(key);
                if (value != null) {
                    found.put(key, value);
                }
            });
            return found;
        }
    }

    private static EnrichmentRun<Order> runWith(List<EnrichmentStage<Order, ?, ?>> stages,
                                                int cacheSize, ExportProperties.Enrichment defaults) {
        return new EnrichmentExecutor(null, defaults, NoopExportMetrics.INSTANCE,
                EngineFixtures.MESSAGES)
                .startRun("catalog.orders", stages, Locale.ENGLISH, cacheSize);
    }

    private static ExportProperties.Enrichment defaults() {
        return new ExportProperties().getEnrichment();
    }

    @SuppressWarnings("unchecked")
    private static EnrichmentStage<Order, ?, ?> customerStage(Enricher<Object, Object> enricher,
                                                              MissingPolicy missing,
                                                              FailurePolicy failure,
                                                              Integer batchSize) {
        return EnrichmentStage.<Order, Object, Object>of("customer", enricher)
                .keyExtractor(Order::customerId)
                .merge((order, value) -> order.withCustomer((String) value))
                .missingPolicy(missing)
                .failurePolicy(failure)
                .batchSize(batchSize)
                .build();
    }

    private static List<Order> orders(int count, int customers) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new Order("O-" + i, "C-" + (i % customers), null))
                .toList();
    }

    @Test
    @DisplayName("a batched stage asks once per chunk of distinct keys, not once per row")
    void batchesDistinctKeys() {
        Map<Object, Object> known = new HashMap<>();
        IntStream.range(0, 10).forEach(i -> known.put("C-" + i, "Customer " + i));
        RecordingPartner partner = new RecordingPartner(known);
        EnrichmentRun<Order> run = runWith(
                List.of(customerStage(partner, MissingPolicy.placeholder(), FailurePolicy.FAIL_REPORT, 4)),
                1_000, defaults());

        WindowEnrichment<Order> window = run.enrich(orders(100, 10));

        assertThat(window.rows()).hasSize(100);
        assertThat(window.rows().get(0).row().customerName()).isNotNull();
        // Ten distinct customers across a hundred rows, in chunks of four: three calls, not a hundred.
        assertThat(partner.calls).hasValue(3);
        assertThat(partner.callSizes).containsExactly(4, 4, 2);
    }

    @Test
    @DisplayName("the second window of the same keys costs no partner calls at all")
    void cachesAcrossWindows() {
        RecordingPartner partner = new RecordingPartner(Map.of("C-0", "Zero", "C-1", "One"));
        EnrichmentRun<Order> run = runWith(
                List.of(customerStage(partner, MissingPolicy.placeholder(), FailurePolicy.FAIL_REPORT, 100)),
                1_000, defaults());

        run.enrich(orders(50, 2));
        int afterFirst = partner.calls.get();
        run.enrich(orders(50, 2));

        assertThat(afterFirst).isEqualTo(1);
        assertThat(partner.calls).hasValue(1);
        assertThat(run.cache().hits()).isEqualTo(2);
        assertThat(run.cache().misses()).isEqualTo(2);
    }

    @Test
    @DisplayName("a key the partner did not know is remembered, so it is not asked about again")
    void cachesAbsence() {
        RecordingPartner partner = new RecordingPartner(Map.of());
        EnrichmentRun<Order> run = runWith(
                List.of(customerStage(partner, MissingPolicy.placeholder(), FailurePolicy.FAIL_REPORT, 100)),
                1_000, defaults());

        run.enrich(orders(10, 2));
        run.enrich(orders(10, 2));

        // One call for the first window; the second window's keys are known to be unknown.
        assertThat(partner.calls).hasValue(1);
    }

    @Test
    @DisplayName("a missing key under PLACEHOLDER marks only that stage's cells")
    void marksMissingKeysWithAPlaceholder() {
        RecordingPartner partner = new RecordingPartner(Map.of("C-0", "Zero"));
        EnrichmentRun<Order> run = runWith(
                List.of(customerStage(partner, MissingPolicy.placeholder("test.placeholder"),
                        FailurePolicy.FAIL_REPORT, 100)),
                1_000, defaults());

        WindowEnrichment<Order> window = run.enrich(orders(4, 2));

        assertThat(window.rows()).hasSize(4);
        Map<Boolean, List<EnrichedRow<Order>>> byMarked = new HashMap<>();
        window.rows().forEach(row -> byMarked
                .computeIfAbsent(row.markers().isEmpty(), key -> new ArrayList<>()).add(row));
        assertThat(byMarked.get(false)).allSatisfy(row ->
                assertThat(row.markers().get("customer"))
                        .isEqualTo(new CellValue.Error("test.placeholder")));
        assertThat(byMarked.get(true)).allSatisfy(row ->
                assertThat(row.row().customerName()).isEqualTo("Zero"));
    }

    @Test
    @DisplayName("a missing key under FAIL_ROW removes the row and reports how many")
    void dropsRowsUnderFailRow() {
        RecordingPartner partner = new RecordingPartner(Map.of("C-0", "Zero"));
        EnrichmentRun<Order> run = runWith(
                List.of(customerStage(partner, MissingPolicy.failRow(), FailurePolicy.FAIL_REPORT, 100)),
                1_000, defaults());

        WindowEnrichment<Order> window = run.enrich(orders(4, 2));

        assertThat(window.rows()).hasSize(2);
        assertThat(window.dropped()).isEqualTo(2);
    }

    @Test
    @DisplayName("a missing key under FAIL_REPORT fails the run, naming the stage but not the key")
    void failsTheRunUnderFailReport() {
        RecordingPartner partner = new RecordingPartner(Map.of());
        EnrichmentRun<Order> run = runWith(
                List.of(customerStage(partner, MissingPolicy.failReport(), FailurePolicy.FAIL_REPORT, 100)),
                1_000, defaults());

        assertThatThrownBy(() -> run.enrich(orders(2, 2)))
                .isInstanceOf(EnrichmentKeyMissingException.class)
                .hasMessageContaining("customer")
                .hasMessageNotContaining("C-0");
    }

    @Test
    @DisplayName("a partner failure under FAIL_REPORT fails the run")
    void failsTheRunOnAPartnerFailure() {
        Enricher.Batched<Object, Object> broken = keys -> {
            throw new IllegalStateException("partner is down");
        };
        EnrichmentRun<Order> run = runWith(
                List.of(customerStage(broken, MissingPolicy.placeholder(), FailurePolicy.FAIL_REPORT, 100)),
                1_000, defaults());

        assertThatThrownBy(() -> run.enrich(orders(2, 2)))
                .isInstanceOf(EnrichmentFailedException.class)
                .hasMessageContaining("customer");
    }

    @Test
    @DisplayName("a partner failure under DEGRADE marks the cells, records the stage, and stops calling")
    void degradesVisiblyAndStopsCalling() {
        AtomicInteger attempts = new AtomicInteger();
        Enricher.Batched<Object, Object> broken = keys -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("partner is down");
        };
        EnrichmentRun<Order> run = runWith(
                List.of(customerStage(broken, MissingPolicy.placeholder(), FailurePolicy.DEGRADE, 100)),
                1_000, defaults());

        WindowEnrichment<Order> first = run.enrich(orders(2, 2));
        WindowEnrichment<Order> second = run.enrich(orders(2, 2));

        assertThat(run.degradedStages()).containsExactly("customer");
        assertThat(first.rows()).allSatisfy(row -> assertThat(row.markers().get("customer"))
                .isEqualTo(new CellValue.Error("ludwig.export.cell.unavailable")));
        assertThat(second.rows()).allSatisfy(row -> assertThat(row.markers()).containsKey("customer"));
        // The partner was given up on after the first failure rather than called again per window.
        assertThat(attempts).hasValue(1);
    }

    @Test
    @DisplayName("a per-item stage asks once per distinct key")
    void walksPerItemStages() {
        AtomicInteger calls = new AtomicInteger();
        Enricher.PerItem<Object, Object> perItem = key -> {
            calls.incrementAndGet();
            return "C-0".equals(key) ? Optional.of("Zero") : Optional.empty();
        };
        EnrichmentRun<Order> run = runWith(
                List.of(customerStage(perItem, MissingPolicy.placeholder(), FailurePolicy.FAIL_REPORT, 100)),
                1_000, defaults());

        WindowEnrichment<Order> window = run.enrich(orders(20, 2));

        assertThat(calls).hasValue(2);
        assertThat(window.rows()).hasSize(20);
    }

    @Test
    @DisplayName("a dimension stage fetches its catalogue once, lazily, for the whole run")
    void fetchesADimensionOnce() {
        AtomicInteger fetches = new AtomicInteger();
        Enricher.Dimension<Object, Object> dimension = () -> {
            fetches.incrementAndGet();
            return Map.of("C-0", "Zero", "C-1", "One");
        };
        EnrichmentRun<Order> run = runWith(
                List.of(customerStage(dimension, MissingPolicy.placeholder(), FailurePolicy.FAIL_REPORT,
                        100)),
                1_000, defaults());

        assertThat(fetches).hasValue(0);
        run.enrich(orders(10, 2));
        run.enrich(orders(10, 2));

        assertThat(fetches).hasValue(1);
    }

    @Test
    @DisplayName("a dimension bigger than declared fails the run rather than being truncated")
    void refusesAnOversizedDimension() {
        Enricher.Dimension<Object, Object> huge = () -> {
            Map<Object, Object> catalogue = new HashMap<>();
            IntStream.range(0, 50).forEach(i -> catalogue.put("C-" + i, "Customer " + i));
            return catalogue;
        };
        EnrichmentStage<Order, ?, ?> stage = EnrichmentStage.<Order, Object, Object>of("customer", huge)
                .keyExtractor(Order::customerId)
                .merge((order, value) -> order.withCustomer((String) value))
                .maxDimensionEntries(10)
                .build();

        EnrichmentRun<Order> run = runWith(List.of(stage), 1_000, defaults());

        assertThatThrownBy(() -> run.enrich(orders(2, 2)))
                .isInstanceOf(DimensionTooLargeException.class)
                .hasMessageContaining("customer");
    }

    @Test
    @DisplayName("a row with no key is left alone, which is not the same as the partner not knowing it")
    void leavesKeylessRowsAlone() {
        RecordingPartner partner = new RecordingPartner(Map.of());
        EnrichmentStage<Order, ?, ?> stage = EnrichmentStage.<Order, Object, Object>of("customer", partner)
                .keyExtractor(order -> null)
                .merge((order, value) -> order)
                .missingPolicy(MissingPolicy.failReport())
                .build();
        EnrichmentRun<Order> run = runWith(List.of(stage), 1_000, defaults());

        WindowEnrichment<Order> window = run.enrich(orders(3, 2));

        // FAIL_REPORT would have thrown if a null key counted as a key the partner did not know.
        assertThat(window.rows()).hasSize(3);
        assertThat(window.rows()).allSatisfy(row -> assertThat(row.markers()).isEmpty());
        assertThat(partner.calls).hasValue(0);
    }

    @Test
    @DisplayName("stages run in dependency order, and a cycle is a configuration failure")
    void ordersStagesByDependency() {
        List<String> order = new ArrayList<>();
        Enricher.Batched<Object, Object> first = keys -> {
            order.add("first");
            return Map.of();
        };
        Enricher.Batched<Object, Object> second = keys -> {
            order.add("second");
            return Map.of();
        };
        EnrichmentStage<Order, ?, ?> stageOne = EnrichmentStage.<Order, Object, Object>of("first", first)
                .keyExtractor(Order::customerId).merge((o, v) -> o).build();
        EnrichmentStage<Order, ?, ?> stageTwo = EnrichmentStage.<Order, Object, Object>of("second", second)
                .keyExtractor(Order::customerId).merge((o, v) -> o)
                .dependsOn(Set.of("first")).build();

        runWith(List.of(stageTwo, stageOne), 1_000, defaults()).enrich(orders(2, 2));

        assertThat(order).containsExactly("first", "second");
    }

    @Test
    @DisplayName("stages that depend on each other are refused rather than looped over")
    void refusesACycle() {
        Enricher.Batched<Object, Object> partner = keys -> Map.of();
        EnrichmentStage<Order, ?, ?> a = EnrichmentStage.<Order, Object, Object>of("a", partner)
                .keyExtractor(Order::customerId).merge((o, v) -> o).dependsOn(Set.of("b")).build();
        EnrichmentStage<Order, ?, ?> b = EnrichmentStage.<Order, Object, Object>of("b", partner)
                .keyExtractor(Order::customerId).merge((o, v) -> o).dependsOn(Set.of("a")).build();

        assertThatThrownBy(() -> runWith(List.of(a, b), 1_000, defaults()))
                .isInstanceOf(ExportConfigurationException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    @DisplayName("a stage with caching switched off asks the partner every window")
    void honoursCacheDisabled() {
        RecordingPartner partner = new RecordingPartner(Map.of("C-0", "Zero", "C-1", "One"));
        EnrichmentStage<Order, ?, ?> stage = EnrichmentStage.<Order, Object, Object>of("customer", partner)
                .keyExtractor(Order::customerId)
                .merge((order, value) -> order.withCustomer((String) value))
                .cacheEnabled(false)
                .build();
        EnrichmentRun<Order> run = runWith(List.of(stage), 1_000, defaults());

        run.enrich(orders(10, 2));
        run.enrich(orders(10, 2));

        assertThat(partner.calls).hasValue(2);
    }
}
