package ru.ludwigandreas.export.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ludwigandreas.export.api.MultiSheetStrategy;
import ru.ludwigandreas.export.api.NoParameters;
import ru.ludwigandreas.export.api.RowSource;
import ru.ludwigandreas.export.api.SheetSpec;
import ru.ludwigandreas.export.api.SourceContext;
import ru.ludwigandreas.export.engine.ExecutionPlan;
import ru.ludwigandreas.export.engine.OutputTarget;
import ru.ludwigandreas.export.engine.ReportRunEngine;
import ru.ludwigandreas.export.engine.ReportRunResult;
import ru.ludwigandreas.export.engine.RunCancellation;
import ru.ludwigandreas.export.unit.EngineFixtures.Sale;

/**
 * The bounded hand-off between the half that reads and enriches and the half that writes.
 *
 * <p>These are the tests that have to run with a real pool, because the property being asserted is
 * that the producer and the consumer are genuinely two threads and that the queue between them is
 * genuinely bounded. Everything else in the engine suite runs on the calling thread on purpose, so
 * that a quoting bug does not present as a concurrency bug.
 */
class WindowHandoffTest {

    /** The producer thread's name, so a test can tell the two halves apart. */
    private static final String PREFETCH_THREAD = "test-prefetch";

    @TempDir
    Path tempDirectory;

    private InMemoryReportSink sink;
    private Clock clock;
    private ExecutorService prefetch;

    @BeforeEach
    void setUp() {
        sink = new InMemoryReportSink();
        clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneId.of("UTC"));
        prefetch = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, PREFETCH_THREAD);
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterEach
    void tearDown() {
        prefetch.shutdownNow();
    }

    private static List<Sale> sales(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new Sale("REF-" + i, LocalDate.of(2026, 1, 1).plusDays(i % 28),
                        BigDecimal.valueOf(i), i % 2 == 0, "north"))
                .toList();
    }

    private ExecutionPlan<NoParameters, Sale> planFor(RowSource<NoParameters, Sale> source,
                                                      int windowSize, int depth) {
        var definition = EngineFixtures.definitionWith(source, List.of());
        SheetSpec spec = SheetSpec.single("data", "Sales", EngineFixtures.columnSpecs());
        OutputTarget output = EngineFixtures.csvOutput(List.of(spec), MultiSheetStrategy.REJECT,
                Map.of("profile", "rfc4180"), false);
        return EngineFixtures.plan(definition, List.of(spec), List.of(output), windowSize, 100_000,
                depth);
    }

    @Test
    @DisplayName("a run across the hand-off produces the same file as one on a single thread")
    void producesTheSameFileAcrossThreads() {
        RowSource<NoParameters, Sale> source = fixed(sales(500));
        ReportRunEngine engine = EngineFixtures.engine(tempDirectory, sink, clock, prefetch);

        ReportRunResult result = engine.execute(planFor(source, 50, 4), RunCancellation.NEVER);

        assertThat(result.rowsWritten()).isEqualTo(500);
        String text = new String(sink.bytes(result.outputs().get(0).stored().uri()),
                StandardCharsets.UTF_8);
        assertThat(text.lines().count()).isEqualTo(501);
        assertThat(text).contains("REF-500");
    }

    @Test
    @DisplayName("the producer never runs more than the queue depth ahead of the writer")
    void respectsTheQueueDepth() {
        int windowSize = 100;
        int depth = 2;
        AtomicInteger rowsRead = new AtomicInteger();
        AtomicInteger windowsConsumed = new AtomicInteger();
        AtomicInteger maxLead = new AtomicInteger();
        Set<String> producerThreads = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Set<String> consumerThreads = java.util.concurrent.ConcurrentHashMap.newKeySet();
        RowSource<NoParameters, Sale> counting = new RowSource<>() {
            @Override
            public Stream<Sale> open(SourceContext<NoParameters> context) {
                return sales(2_000).stream().peek(row -> {
                    producerThreads.add(Thread.currentThread().getName());
                    rowsRead.incrementAndGet();
                });
            }

            @Override
            public Set<String> sortableColumns() {
                return Set.of();
            }
        };
        // The cancellation probe is asked on both threads - by the consumer before it writes a
        // window, and by the producer before it reads one, which is what stops a cancelled run from
        // continuing to call partners while the queue drains. Only the consumer's observations are
        // used here: at the moment the consumer takes window N, how many rows has the producer read
        // beyond it? That difference is exactly what the queue bounds, and an unbounded producer
        // would have read to the end of the source by the first observation.
        RunCancellation probe = () -> {
            String thread = Thread.currentThread().getName();
            if (PREFETCH_THREAD.equals(thread)) {
                producerThreads.add(thread);
                return false;
            }
            consumerThreads.add(thread);
            int consumed = windowsConsumed.incrementAndGet();
            maxLead.accumulateAndGet(rowsRead.get() - (consumed * windowSize), Math::max);
            return false;
        };
        ReportRunEngine engine = EngineFixtures.engine(tempDirectory, sink, clock, prefetch);

        ReportRunResult result = engine.execute(planFor(counting, windowSize, depth), probe);

        assertThat(result.rowsWritten()).isEqualTo(2_000);
        // The queue holds `depth` finished windows; the producer may also be filling one more. How
        // far ahead it actually gets is a scheduling question and is deliberately not asserted - the
        // bound is the contract, and a producer that happens to stay level with the consumer is
        // honouring it.
        assertThat(maxLead.get()).isLessThanOrEqualTo((depth + 1) * windowSize);
        // What is not a scheduling question: the two halves ran on different threads, which is the
        // whole reason the queue exists.
        assertThat(producerThreads).containsExactly(PREFETCH_THREAD);
        assertThat(consumerThreads).isNotEmpty().doesNotContain(PREFETCH_THREAD);
    }

    @Test
    @DisplayName("a failure on the producer thread surfaces on the caller's, with the file cleaned up")
    void propagatesProducerFailures() {
        RowSource<NoParameters, Sale> exploding = new RowSource<>() {
            @Override
            public Stream<Sale> open(SourceContext<NoParameters> context) {
                return Stream.concat(sales(10).stream(), Stream.generate(() -> {
                    throw new IllegalStateException("the query died");
                }));
            }

            @Override
            public Set<String> sortableColumns() {
                return Set.of();
            }
        };
        ReportRunEngine engine = EngineFixtures.engine(tempDirectory, sink, clock, prefetch);

        assertThatThrownBy(() -> engine.execute(planFor(exploding, 5, 2), RunCancellation.NEVER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the query died");

        assertThat(sink.size()).isZero();
        assertThat(listTempFiles()).isEmpty();
    }

    @Test
    @DisplayName("the source stream is closed even when the producer is interrupted mid-run")
    void closesTheStreamWhenTheProducerIsCancelled() {
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        RowSource<NoParameters, Sale> slow = new RowSource<>() {
            @Override
            public Stream<Sale> open(SourceContext<NoParameters> context) {
                return Stream.iterate(sales(1).get(0), sale -> sale).onClose(closed::countDown);
            }

            @Override
            public Set<String> sortableColumns() {
                return Set.of();
            }
        };
        ReportRunEngine engine = EngineFixtures.engine(tempDirectory, sink, clock, prefetch);

        assertThatThrownBy(() -> engine.execute(planFor(slow, 10, 2), () -> true))
                .isInstanceOf(RuntimeException.class);

        assertThat(awaitClose(closed)).isTrue();
        assertThat(listTempFiles()).isEmpty();
    }

    private boolean awaitClose(java.util.concurrent.CountDownLatch latch) {
        try {
            return latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static RowSource<NoParameters, Sale> fixed(List<Sale> rows) {
        return new RowSource<>() {
            @Override
            public Stream<Sale> open(SourceContext<NoParameters> context) {
                return rows.stream();
            }

            @Override
            public Set<String> sortableColumns() {
                return Set.of();
            }
        };
    }

    private List<Path> listTempFiles() {
        try (Stream<Path> entries = Files.list(tempDirectory)) {
            return entries.toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
