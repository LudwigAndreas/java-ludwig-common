package ru.ludwigandreas.messaging.db.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static ru.ludwigandreas.messaging.db.integration.LogOnlyTestApplication.BROKEN;
import static ru.ludwigandreas.messaging.db.integration.LogOnlyTestApplication.CORRELATED;
import static ru.ludwigandreas.messaging.db.integration.LogOnlyTestApplication.DEDUPED;
import static ru.ludwigandreas.messaging.db.integration.LogOnlyTestApplication.ORDERED;
import static ru.ludwigandreas.messaging.db.integration.LogOnlyTestApplication.ORDERS;
import static ru.ludwigandreas.messaging.db.integration.LogOnlyTestApplication.TEXT;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.messaging.api.MessageHeaders;
import ru.ludwigandreas.messaging.audit.DeadLetterAudit;
import ru.ludwigandreas.messaging.db.integration.LogOnlyTestApplication.Recorder;
import ru.ludwigandreas.messaging.db.log.LogStore;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.api.OutboxEventPublisher;
import ru.ludwigandreas.outbox.scheduler.OutboxProcessingService;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

/**
 * A service with the starter, every consumed topic listed, and no broker anywhere.
 *
 * <p>{@code spring.kafka.bootstrap-servers} points at a port nothing listens on, so any path that still
 * reached for a broker - the outbox's send, a container's poll, a dead-letter publish - would fail or hang
 * here rather than pass by accident.
 */
@SpringBootTest(classes = LogOnlyTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresContainerConfiguration.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        // The application has no changelog of its own; every module installs its schema with its own bean.
        "spring.liquibase.enabled=false",
        "spring.kafka.bootstrap-servers=localhost:9",
        "spring.kafka.consumer.auto-offset-reset=earliest",

        "ludwig.messaging.db.consume=it.orders,it.text,it.ordered,it.deduped,it.broken,it.correlated",
        "ludwig.messaging.db.poll-interval=50ms",
        "ludwig.messaging.db.partitions=4",

        "ludwig.messaging.consumers.typed.dedup=false",
        "ludwig.messaging.consumers.typed.accepted-versions.max=1",
        "ludwig.messaging.consumers.text.dedup=false",
        "ludwig.messaging.consumers.text.retry.initial-backoff=100ms",
        "ludwig.messaging.consumers.text.retry.max-backoff=500ms",
        "ludwig.messaging.consumers.text.retry.max-attempts=3",
        "ludwig.messaging.consumers.ordered.dedup=false",
        "ludwig.messaging.consumers.ordered.retry.initial-backoff=50ms",
        "ludwig.messaging.consumers.broken.dedup=false",
        "ludwig.messaging.consumers.broken.retry.initial-backoff=50ms",
        "ludwig.messaging.consumers.broken.retry.max-backoff=200ms",
        "ludwig.messaging.consumers.broken.retry.max-attempts=2",
        // Longer than the log's topic column, so every dead-letter append for this consumer fails.
        "ludwig.messaging.consumers.broken.dead-letter.suffix=.dlt-"
                + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",

        "ludwig.outbox.polling.enabled=false",
        "ludwig.outbox.routes.orders.transport=kafka",
        "ludwig.outbox.routes.orders.destination=it.orders",
        "ludwig.outbox.routes.orders.event-types=OrderPlaced"
})
class LogOnlyTransportIT {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    private OutboxEventPublisher publisher;
    @Autowired
    private OutboxProcessingService processing;
    @Autowired
    private TransactionTemplate transaction;
    @Autowired
    private LogStore store;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Recorder recorder;
    @Autowired
    private MeterRegistry meters;

    @Test
    void anOutboxEventIsAppendedWithItsEnvelopeAndConsumedWithoutABroker() {
        transaction.executeWithoutResult(status -> publisher.publish(OutboxEvent.builder()
                .aggregateType("Order").aggregateId("o-1").eventType("OrderPlaced")
                .idempotencyKey("order-o-1").payload(Map.of("id", "o-1", "action", "place")).build()));

        assertThat(processing.processBatch()).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap("SELECT record_key, headers::text AS headers FROM "
                + "ludwig_messaging.message_log WHERE topic = ? AND record_key = ?", ORDERS, bytes("o-1"));
        assertThat((String) row.get("headers"))
                .contains("\"" + MessageHeaders.EVENT_TYPE + "\"", "\"OrderPlaced\"")
                .contains("\"" + MessageHeaders.IDEMPOTENCY_KEY + "\"", "\"order-o-1\"")
                .contains("\"" + MessageHeaders.PRODUCED_AT + "\"");
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_message WHERE aggregate_id = 'o-1'", String.class))
                .isEqualTo("PUBLISHED");
        await().atMost(WAIT).untilAsserted(() -> assertThat(recorder.on("billing")).contains("o-1"));
    }

    @Test
    void everyGroupReceivesEveryRecordOnce() {
        append(ORDERS, "fan", "{\"id\":\"fan-1\",\"action\":\"x\"}", Map.of());

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(recorder.on("billing")).containsOnlyOnce("fan-1");
            assertThat(recorder.on("search")).containsOnlyOnce("fan-1");
        });
    }

    @Test
    void aFailingRecordIsDeliveredTheAttemptBudgetThenDeadLettered() {
        recorder.failing.add("doomed");
        double before = counter("ludwig.messaging.dead.lettered");
        append(TEXT, "k", "doomed", Map.of(MessageHeaders.EVENT_TYPE, "Thing"));

        await().atMost(WAIT).untilAsserted(() -> assertThat(dltPayloads(TEXT + ".dlt")).contains("doomed"));
        assertThat(recorder.attempts.get("doomed").get()).isEqualTo(3);
        assertThat(counter("ludwig.messaging.dead.lettered")).isEqualTo(before + 1);
        assertThat(recorder.audits).extracting(AuditEvent::action).contains(DeadLetterAudit.ACTION_DEAD_LETTERED);
        await().atMost(WAIT).untilAsserted(() -> assertThat(attemptsOf("it-text", TEXT)).allMatch(a -> a == 0));
    }

    @Test
    void aPoisonPayloadIsNeverDeliveredAndKeepsItsBytes() {
        byte[] poison = "{not json".getBytes(StandardCharsets.UTF_8);
        store.append(ORDERS, bytes("poison"), new RecordHeaders(), poison);

        await().atMost(WAIT).untilAsserted(() -> assertThat(
                jdbc.queryForList("SELECT payload FROM ludwig_messaging.message_log WHERE topic = ?",
                        byte[].class, ORDERS + ".dlt")).anySatisfy(payload -> assertThat(payload).isEqualTo(poison)));
    }

    @Test
    void anUnsupportedEventVersionIsDeadLetteredWithoutDelivery() {
        append(ORDERS, "v2", "{\"id\":\"v2-order\",\"action\":\"x\"}", Map.of(MessageHeaders.EVENT_VERSION, "2"));

        await().atMost(WAIT).untilAsserted(() -> assertThat(dltPayloads(ORDERS + ".dlt"))
                .anySatisfy(payload -> assertThat(payload).contains("v2-order")));
        assertThat(recorder.on("billing")).doesNotContain("v2-order");
    }

    @Test
    void aFailedDeadLetterWriteLeavesThePositionWhereItWas() {
        double before = counter("ludwig.messaging.dropped");
        append(BROKEN, "b", "stuck", Map.of());

        await().atMost(WAIT).untilAsserted(() -> assertThat(counter("ludwig.messaging.dropped")).isGreaterThan(before));
        assertThat(jdbc.queryForList("SELECT committed_offset FROM ludwig_messaging.consumer_offset"
                + " WHERE group_id = 'it-broken' AND log_partition = ?", Long.class, store.partitionFor(bytes("b"))))
                .containsExactly(-1L);
        assertThat(recorder.audits).extracting(AuditEvent::action).contains(DeadLetterAudit.ACTION_DROPPED);
    }

    @Test
    void theDedupFilterAppliesToLogRecords() {
        append(DEDUPED, "d", "first", Map.of(MessageHeaders.IDEMPOTENCY_KEY, "same-key"));
        append(DEDUPED, "d", "second", Map.of(MessageHeaders.IDEMPOTENCY_KEY, "same-key"));
        append(DEDUPED, "d", "third", Map.of(MessageHeaders.IDEMPOTENCY_KEY, "other-key"));

        await().atMost(WAIT).untilAsserted(() -> assertThat(recorder.on(DEDUPED)).contains("third"));
        assertThat(recorder.on(DEDUPED)).containsExactly("first", "third");
    }

    @Test
    void theRecordInterceptorsRunAroundTheListener() {
        append(CORRELATED, "c", "hello", Map.of(MessageHeaders.CORRELATION_ID, "c-42"));

        await().atMost(WAIT).untilAsserted(() -> assertThat(recorder.on(CORRELATED)).contains("hello@c-42"));
    }

    @Test
    void recordsOfOneKeyStayInOrderAcrossConcurrentWorkersAndRetries() {
        List<String> keys = List.of("alpha", "beta", "gamma");
        for (int i = 0; i < 10; i++) {
            for (String key : keys) {
                append(ORDERED, key, key + "-" + i + (i == 3 ? "!" : ""), Map.of());
            }
        }

        await().atMost(WAIT).untilAsserted(() -> keys.forEach(key ->
                assertThat(recorder.on(ORDERED + ":" + key)).hasSize(10)));
        for (String key : keys) {
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                expected.add(key + "-" + i + (i == 3 ? "!" : ""));
            }
            assertThat(recorder.on(ORDERED + ":" + key)).containsExactlyElementsOf(expected);
        }
    }

    private void append(String topic, String key, String value, Map<String, String> headers) {
        RecordHeaders recordHeaders = new RecordHeaders();
        headers.forEach((name, text) -> recordHeaders.add(name, bytes(text)));
        store.append(topic, bytes(key), recordHeaders, bytes(value));
    }

    private List<String> dltPayloads(String topic) {
        return jdbc.queryForList("SELECT payload FROM ludwig_messaging.message_log WHERE topic = ?",
                        byte[].class, topic).stream()
                .map(payload -> new String(payload, StandardCharsets.UTF_8)).toList();
    }

    private List<Integer> attemptsOf(String group, String topic) {
        return jdbc.queryForList("SELECT attempts FROM ludwig_messaging.consumer_offset"
                + " WHERE group_id = ? AND topic = ?", Integer.class, group, topic);
    }

    private double counter(String name) {
        return meters.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
