package ru.ludwigandreas.messaging.db.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static ru.ludwigandreas.messaging.db.integration.MixedTestApplication.KAFKA_TOPIC;
import static ru.ludwigandreas.messaging.db.integration.MixedTestApplication.LOG_TOPIC;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import ru.ludwigandreas.messaging.db.integration.MixedTestApplication.Received;
import ru.ludwigandreas.messaging.db.log.LogStore;
import ru.ludwigandreas.testsupport.container.Containers;
import ru.ludwigandreas.testsupport.container.KafkaPropertiesInitializer;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

/**
 * Active mode with a reachable broker: a consumer on the replaced cluster reads the log, a consumer on another
 * cluster reads that cluster, and the partner consumer's dead letters, addressed to the replaced cluster, land
 * in the log.
 */
@SpringBootTest(classes = MixedTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresContainerConfiguration.class)
@ContextConfiguration(initializers = KafkaPropertiesInitializer.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "ludwig.kafka.clusters.internal.client-id=it-internal",
        "ludwig.messaging.db.poll-interval=50ms",
        "ludwig.messaging.consumers.catalog.cluster=internal",
        "ludwig.messaging.consumers.catalog.dedup=false",
        "ludwig.messaging.consumers.partner.dedup=false",
        "ludwig.messaging.consumers.partner.retry.initial-backoff=100ms",
        "ludwig.messaging.consumers.partner.retry.max-attempts=2",
        "ludwig.messaging.consumers.partner.dead-letter.cluster=internal",
        "ludwig.outbox.polling.enabled=false"
})
class MixedTransportIT {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired
    private LogStore store;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Received received;

    @Test
    void eachConsumerReadsItsOwnCluster() throws Exception {
        store.append(LOG_TOPIC, bytes("k"), new RecordHeaders(), bytes("from-log"));
        kafka().send(KAFKA_TOPIC, "k", "from-kafka").get();

        await().atMost(WAIT).untilAsserted(() -> assertThat(received.values)
                .contains(LOG_TOPIC + ":from-log", KAFKA_TOPIC + ":from-kafka"));
    }

    @Test
    void aPartnerRecordDeadLetteredToTheReplacedClusterLandsInTheLogAndNotOnKafka() throws Exception {
        kafka().send(KAFKA_TOPIC, "k", "fail").get();

        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForList(
                "SELECT payload FROM ludwig_messaging.message_log WHERE topic = ?", byte[].class, KAFKA_TOPIC + ".dlt"))
                .extracting(payload -> new String(payload, StandardCharsets.UTF_8)).contains("fail"));
        assertThat(received.values.stream().filter((KAFKA_TOPIC + ":fail")::equals)).hasSize(2);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Containers.kafka().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-dlt-probe",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KAFKA_TOPIC + ".dlt"));
            assertThat(consumer.poll(Duration.ofSeconds(5)).count()).isZero();
        }
    }

    private static KafkaTemplate<String, String> kafka() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Containers.kafka().getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
