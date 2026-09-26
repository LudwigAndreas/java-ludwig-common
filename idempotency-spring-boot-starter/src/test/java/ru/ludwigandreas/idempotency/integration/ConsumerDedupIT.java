package ru.ludwigandreas.idempotency.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.idempotency.api.IdempotencyStore;
import ru.ludwigandreas.idempotency.api.RequestFingerprint;
import ru.ludwigandreas.idempotency.config.IdempotencyProperties;
import ru.ludwigandreas.idempotency.kafka.IdempotentRecordFilterStrategy;
import ru.ludwigandreas.idempotency.metrics.IdempotencyMetrics;

/**
 * Case 8: the same record delivered twice produces one effect.
 *
 * <h2>Why this runs without a broker</h2>
 *
 * <p>What is under test is the dedup decision, not Kafka. A redelivery is a {@code ConsumerRecord} with the
 * same coordinates arriving at the filter a second time, and constructing that directly tests exactly the
 * thing that has to be true - while an embedded broker would add a minute to the build to reproduce a
 * situation this test can state in one line. The container that matters here is Postgres, because the claim
 * is where the correctness lives.
 *
 * <p>The transaction is real, though, and that is the half worth being careful about: the filter claims in
 * {@code TRANSACTIONAL} mode, so it only works inside the listener container's transaction. Running it inside
 * a {@code TransactionTemplate} here is what a container configured with a transaction manager provides, and
 * the rollback case below is the one that would silently lose a message if the claim committed independently.
 */
@SpringBootTest(classes = TestApplication.class,
        properties = {
            "ludwig.idempotency.kafka.enabled=true",
            "ludwig.idempotency.purge.enabled=false"
        })
class ConsumerDedupIT extends PostgresBackedTest {

    private static final String TOPIC = "platform.orders";

    @Autowired
    private IdempotentRecordFilterStrategy strategy;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private RequestFingerprint fingerprints;

    private TransactionTemplate transactions;

    /**
     * A second strategy, keyed on a record header rather than on the coordinates.
     *
     * <p>Constructed here with its own properties rather than configured on the context: the two forms have
     * to be compared in one suite, and a second context differing by one property would double the container
     * time to say the same thing.
     */
    private IdempotentRecordFilterStrategy headerKeyed;

    /** Counts what the listener body would have done, which is the only thing worth asserting. */
    private final AtomicInteger effects = new AtomicInteger();

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM idempotency_claim");
        transactions = new TransactionTemplate(transactionManager);
        effects.set(0);

        IdempotencyProperties headerKeyedProperties = new IdempotencyProperties();
        headerKeyedProperties.getKafka().setKeyHeader("event-id");
        headerKeyed = new IdempotentRecordFilterStrategy(store, fingerprints, headerKeyedProperties,
                IdempotencyMetrics.NOOP);
    }

    @Test
    @DisplayName("the same record delivered twice produces one effect")
    void redeliveryProducesOneEffect() {
        ConsumerRecord<Object, Object> record = record(42L, "{\"order\":1}");

        deliver(record);
        deliver(record);

        assertThat(effects.get())
                .describedAs("at-least-once delivery must stop meaning at-least-once effect")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("two different records both produce an effect")
    void distinctRecordsBothRun() {
        deliver(record(1L, "{\"order\":1}"));
        deliver(record(2L, "{\"order\":2}"));

        assertThat(effects.get()).isEqualTo(2);
    }

    /**
     * The reason the claim must be in the listener's transaction.
     *
     * <p>A claim that committed independently would survive the rollback, and Kafka - which did not commit
     * the offset either - would redeliver the record straight into a claim that discards it. The message
     * would never be delivered and nothing would say so. This asserts the opposite: the rollback takes the
     * claim with it and the redelivery is processed.
     */
    @Test
    @DisplayName("a listener whose transaction rolls back gets the record again")
    void rollbackLetsTheRedeliveryThrough() {
        ConsumerRecord<Object, Object> record = record(7L, "{\"order\":7}");

        transactions.execute(status -> {
            if (!strategy.filter(record)) {
                effects.incrementAndGet();
            }
            status.setRollbackOnly();
            return null;
        });

        assertThat(effects.get()).isEqualTo(1);
        assertThat(claims()).isZero();

        deliver(record);
        assertThat(effects.get())
                .describedAs("the redelivery of a rolled-back unit of work must be processed")
                .isEqualTo(2);
    }

    /**
     * A producer-supplied key reusing one value for two payloads is the producer's bug, and the record is
     * discarded rather than guessed at.
     */
    @Test
    @DisplayName("a producer-supplied key reused for a different payload is refused")
    void reusedHeaderKeyIsRefused() {
        ConsumerRecord<Object, Object> first = record(1L, "{\"order\":1}");
        first.headers().add(new RecordHeader("event-id", "e-1".getBytes(StandardCharsets.UTF_8)));
        ConsumerRecord<Object, Object> second = record(2L, "{\"order\":999}");
        second.headers().add(new RecordHeader("event-id", "e-1".getBytes(StandardCharsets.UTF_8)));

        // The header form only matters when the producer republishes one logical event; the coordinates
        // cannot collide, which is why the default is the coordinates.
        assertThat(deliverWithHeaderKey(first)).isTrue();
        assertThat(deliverWithHeaderKey(second)).isFalse();
        assertThat(effects.get()).isEqualTo(1);
    }

    /** Runs the filter inside a transaction, incrementing the effect counter when the record is kept. */
    private void deliver(ConsumerRecord<Object, Object> record) {
        transactions.executeWithoutResult(status -> {
            if (!strategy.filter(record)) {
                effects.incrementAndGet();
            }
        });
    }

    /**
     * The same, against a strategy whose key comes from a record header.
     *
     * @return whether the record was kept
     */
    private boolean deliverWithHeaderKey(ConsumerRecord<Object, Object> record) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            boolean kept = !headerKeyed.filter(record);
            if (kept) {
                effects.incrementAndGet();
            }
            return kept;
        }));
    }

    private long claims() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM idempotency_claim", Long.class);
        return count == null ? 0L : count;
    }

    private static ConsumerRecord<Object, Object> record(long offset, String value) {
        return new ConsumerRecord<>(TOPIC, 0, offset, "order-key", value);
    }
}
