package ru.ludwigandreas.messaging.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static ru.ludwigandreas.messaging.integration.MultiClusterTestApplication.FAILING;
import static ru.ludwigandreas.messaging.integration.MultiClusterTestApplication.FAILING_ELSEWHERE;
import static ru.ludwigandreas.messaging.integration.MultiClusterTestApplication.ORDERS;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import ru.ludwigandreas.messaging.integration.MultiClusterTestApplication.Received;
import ru.ludwigandreas.testsupport.container.Containers;
import ru.ludwigandreas.testsupport.image.LudwigTestImages;

/**
 * Two real brokers: the shared one as {@code default}, and a second one as {@code partner-a}.
 *
 * <p>Two brokers rather than two topics on one, because every behaviour here is about which cluster a
 * record is read from or written to, and one broker would let a misrouted record pass for a correct one.
 */
@SpringBootTest(classes = MultiClusterTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "ludwig.messaging.consumers.default-orders.dedup=true",
                "ludwig.messaging.consumers.partner-orders.cluster=partner-a",
                "ludwig.messaging.consumers.partner-orders.dedup=true",
                "ludwig.messaging.consumers.partner-failing.cluster=partner-a",
                "ludwig.messaging.consumers.partner-failing.dedup=false",
                "ludwig.messaging.consumers.partner-failing.retry.initial-backoff=50ms",
                "ludwig.messaging.consumers.partner-failing.retry.max-attempts=2",
                "ludwig.messaging.consumers.partner-failing-elsewhere.cluster=partner-a",
                "ludwig.messaging.consumers.partner-failing-elsewhere.dead-letter.cluster=default",
                "ludwig.messaging.consumers.partner-failing-elsewhere.dedup=false",
                "ludwig.messaging.consumers.partner-failing-elsewhere.retry.initial-backoff=50ms",
                "ludwig.messaging.consumers.partner-failing-elsewhere.retry.max-attempts=2"
        })
class MultiClusterConsumerIT {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @SuppressWarnings("resource") // Stopped by Testcontainers' reaper with the JVM.
    private static final KafkaContainer PARTNER = new KafkaContainer(LudwigTestImages.KAFKA);

    static {
        PARTNER.start();
    }

    @DynamicPropertySource
    static void clusters(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> Containers.kafka().getBootstrapServers());
        registry.add("ludwig.kafka.clusters.partner-a.bootstrap-servers", PARTNER::getBootstrapServers);
    }

    @Autowired
    private Received received;

    @Autowired
    private MeterRegistry meters;

    /**
     * Same topic name on both clusters, first record of each, so the same partition and offset too - the
     * coordinates the dedup filter falls back to when a record has no key header. Unscoped by cluster, the
     * second would be discarded as a duplicate of the first.
     */
    @Test
    void eachClusterIsReadByItsOwnConsumerAndDedupDoesNotConfuseThem() {
        send(Containers.kafka().getBootstrapServers(), ORDERS, "on-default");
        send(PARTNER.getBootstrapServers(), ORDERS, "on-partner");

        await().atMost(WAIT).untilAsserted(() -> assertThat(received.values)
                .contains("default:on-default", "partner-a:on-partner"));
        assertThat(received.values).doesNotContain("default:on-partner", "partner-a:on-default");
    }

    @Test
    void aDeadLetterGoesBackToTheSourceClusterByDefault() {
        send(PARTNER.getBootstrapServers(), FAILING, "doomed");

        assertThat(poll(PARTNER.getBootstrapServers(), FAILING + ".dlt")).contains("doomed");
        assertThat(meters.find("ludwig.messaging.dead.lettered").tag("cluster", "partner-a")
                .tag("topic", FAILING).counter()).isNotNull();
    }

    @Test
    void aDeadLetterGoesToTheConfiguredClusterAndNotTheSource() {
        send(PARTNER.getBootstrapServers(), FAILING_ELSEWHERE, "redirected");

        assertThat(poll(Containers.kafka().getBootstrapServers(), FAILING_ELSEWHERE + ".dlt")).contains("redirected");
        assertThat(poll(PARTNER.getBootstrapServers(), FAILING_ELSEWHERE + ".dlt", Duration.ofSeconds(5))).isEmpty();
    }

    private static void send(String bootstrap, String topic, String value) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap), new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, value)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> poll(String bootstrap, String topic) {
        List<String> values = new ArrayList<>();
        await().atMost(WAIT).until(() -> {
            values.addAll(poll(bootstrap, topic, Duration.ofSeconds(2)));
            return !values.isEmpty();
        });
        return values;
    }

    private static List<String> poll(String bootstrap, String topic, Duration duration) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, "probe-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            List<String> values = new ArrayList<>();
            for (ConsumerRecord<String, String> record : consumer.poll(duration)) {
                values.add(record.value());
            }
            return values;
        }
    }
}
