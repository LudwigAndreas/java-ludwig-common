package ru.ludwigandreas.messaging.db.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static ru.ludwigandreas.messaging.db.integration.MixedTestApplication.LOG_TOPIC;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.messaging.db.integration.MixedTestApplication.Received;
import ru.ludwigandreas.messaging.db.log.LogStore;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.api.OutboxEventPublisher;
import ru.ludwigandreas.outbox.scheduler.OutboxProcessingService;
import ru.ludwigandreas.testsupport.container.KafkaPropertiesInitializer;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

/** The cutover: writes go to Kafka, and what was already in the log is still delivered until the lag is zero. */
@SpringBootTest(classes = MixedTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresContainerConfiguration.class)
@ContextConfiguration(initializers = KafkaPropertiesInitializer.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "ludwig.messaging.db.mode=draining",
        "ludwig.messaging.db.consume=" + LOG_TOPIC,
        "ludwig.messaging.db.poll-interval=50ms",
        // Its own group: the active-mode context may still be running against the same database.
        "it.group=it-draining",
        "ludwig.messaging.consumers.mixed.dedup=false",
        "ludwig.outbox.polling.enabled=false",
        "ludwig.outbox.routes.mixed.transport=kafka",
        "ludwig.outbox.routes.mixed.destination=" + LOG_TOPIC,
        "ludwig.outbox.routes.mixed.event-types=Mixed"
})
class DrainingIT {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired
    private LogStore store;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Received received;
    @Autowired
    private OutboxEventPublisher publisher;
    @Autowired
    private OutboxProcessingService processing;
    @Autowired
    private TransactionTemplate transaction;

    @Test
    void theBacklogDrainsFromTheLogWhileNewWritesGoToKafka() {
        for (int i = 0; i < 10; i++) {
            store.append(LOG_TOPIC, bytes("k" + i), new RecordHeaders(), bytes("backlog-" + i));
        }
        transaction.executeWithoutResult(status -> publisher.publish(OutboxEvent.builder()
                .aggregateType("Mixed").aggregateId("after-switch").eventType("Mixed")
                .payload(Map.of("id", "after-switch")).build()));
        assertThat(processing.processBatch()).isEqualTo(1);

        await().atMost(WAIT).untilAsserted(() -> {
            for (int i = 0; i < 10; i++) {
                assertThat(received.values).contains(LOG_TOPIC + ":backlog-" + i);
            }
            assertThat(received.values).anySatisfy(value -> assertThat(value).contains("after-switch"));
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ludwig_messaging.message_log WHERE record_key = ?",
                Long.class, (Object) bytes("after-switch"))).as("the write went to Kafka, not the log").isZero();
        await().atMost(WAIT).untilAsserted(() -> assertThat(store.lag())
                .filteredOn(lag -> lag.groupId().equals("it-draining"))
                .isNotEmpty().allSatisfy(lag -> assertThat(lag.records()).isZero()));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
