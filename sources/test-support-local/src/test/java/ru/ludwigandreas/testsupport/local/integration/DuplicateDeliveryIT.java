package ru.ludwigandreas.testsupport.local.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import ru.ludwigandreas.testsupport.container.KafkaPropertiesInitializer;
import ru.ludwigandreas.testsupport.local.fixture.KafkaDeliveries;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleKafkaConfiguration;

/**
 * Proves a redelivery can be produced on demand, and looks to the listener the way a real one does.
 *
 * <p>Each test uses a key of its own, because the broker is shared by the whole suite and the topic
 * keeps what earlier classes published.
 */
@SampleContext
@ContextConfiguration(initializers = KafkaPropertiesInitializer.class)
@TestPropertySource(properties = "sample.kafka.enabled=true")
class DuplicateDeliveryIT {

    private static final Duration ARRIVAL = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private SampleKafkaConfiguration.Received received;

    @Test
    @DisplayName("a duplicated record arrives twice with the same key, headers and payload at different offsets")
    void deliversTheSameRecordTwice() {
        String key = "duplicate-" + UUID.randomUUID();
        ProducerRecord<String, String> record =
                new ProducerRecord<>(SampleKafkaConfiguration.TOPIC, key, "widget alpha changed");
        record.headers().add(new RecordHeader("x-sample-event-id", "event-1".getBytes(StandardCharsets.UTF_8)));

        KafkaDeliveries.duplicate(kafka, record);

        await().atMost(ARRIVAL).untilAsserted(() -> assertThat(received.withKey(key)).hasSize(2));
        List<ConsumerRecord<String, String>> copies = received.withKey(key);
        ConsumerRecord<String, String> first = copies.get(0);
        ConsumerRecord<String, String> second = copies.get(1);
        assertThat(second.value()).isEqualTo(first.value()).isEqualTo("widget alpha changed");
        assertThat(second.headers().lastHeader("x-sample-event-id").value())
                .isEqualTo(first.headers().lastHeader("x-sample-event-id").value())
                .isEqualTo("event-1".getBytes(StandardCharsets.UTF_8));
        assertThat(second.offset()).isNotEqualTo(first.offset());
    }

    @Test
    @DisplayName("seed records are published in order and are on the broker when the call returns")
    void publishesSeedRecordsInOrder() {
        String key = "seed-" + UUID.randomUUID();

        KafkaDeliveries.publish(kafka, List.of(
                new ProducerRecord<>(SampleKafkaConfiguration.TOPIC, key, "first"),
                new ProducerRecord<>(SampleKafkaConfiguration.TOPIC, key, "second"),
                new ProducerRecord<>(SampleKafkaConfiguration.TOPIC, key, "third")));

        await().atMost(ARRIVAL).untilAsserted(() -> assertThat(received.withKey(key)).hasSize(3));
        assertThat(received.withKey(key)).extracting(ConsumerRecord::value)
                .containsExactly("first", "second", "third");
    }
}
