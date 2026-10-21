package ru.ludwigandreas.outbox.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import ru.ludwigandreas.audit.CorrelationProvider;
import ru.ludwigandreas.outbox.api.OutboxEvent;
import ru.ludwigandreas.outbox.integration.testmodel.TransactionalPublishHelper;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;
import ru.ludwigandreas.outbox.scheduler.OutboxProcessingService;
import ru.ludwigandreas.testsupport.container.Containers;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;
import ru.ludwigandreas.testsupport.image.LudwigTestImages;

/** Routes over {@code kafka} and {@code kafka:partner-a}, against two real brokers. */
@SpringBootTest(classes = OutboxTestApplication.class, properties = {
        "ludwig.outbox.routes.partner.transport=kafka:partner-a",
        "ludwig.outbox.routes.partner.destination=it.mco.invoices",
        "ludwig.outbox.routes.partner.event-types=InvoiceIssued",
        "ludwig.outbox.routes.plain.transport=kafka",
        "ludwig.outbox.routes.plain.destination=it.mco.orders",
        "ludwig.outbox.routes.plain.event-types=OrderPlaced"
})
@Import({PostgresContainerConfiguration.class, MultiClusterOutboxTest.Correlation.class})
class MultiClusterOutboxTest {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final ThreadLocal<String> CORRELATION = new ThreadLocal<>();

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

    /** Stands in for observability's provider: the id of the request the test pretends to be. */
    @TestConfiguration
    static class Correlation {

        @Bean
        CorrelationProvider testCorrelationProvider() {
            return () -> Optional.ofNullable(CORRELATION.get());
        }
    }

    @Autowired
    private TransactionalPublishHelper publisher;
    @Autowired
    private OutboxProcessingService processing;
    @Autowired
    private OutboxMessageRepository repository;

    @Test
    void eachRouteWritesToItsOwnClusterWithThePublishersCorrelationId() {
        repository.deleteAll();
        CORRELATION.set("c-7");
        try {
            publisher.publish(event("InvoiceIssued", "inv-1"));
            publisher.publish(event("OrderPlaced", "ord-1"));
        } finally {
            CORRELATION.remove();
        }

        await().atMost(WAIT).until(() -> {
            processing.processBatch();
            return repository.findAll().stream().allMatch(m -> m.getPublishedAt() != null);
        });

        ConsumerRecord<String, String> invoice = first(PARTNER.getBootstrapServers(), "it.mco.invoices");
        assertThat(invoice.key()).isEqualTo("inv-1");
        assertThat(header(invoice, "X-Correlation-Id")).isEqualTo("c-7");
        assertThat(first(Containers.kafka().getBootstrapServers(), "it.mco.orders").key()).isEqualTo("ord-1");
        assertThat(records(Containers.kafka().getBootstrapServers(), "it.mco.invoices", Duration.ofSeconds(3)))
                .as("nothing for the partner route on the default cluster").isEmpty();
        assertThat(repository.findAll()).extracting(m -> m.getTransport())
                .containsExactlyInAnyOrder("kafka:partner-a", "kafka");
    }

    private static OutboxEvent event(String type, String id) {
        return OutboxEvent.builder().aggregateType("Doc").aggregateId(id).eventType(type)
                .payload(Map.of("id", id)).build();
    }

    private static ConsumerRecord<String, String> first(String bootstrap, String topic) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        await().atMost(WAIT).until(() -> {
            found.addAll(records(bootstrap, topic, Duration.ofSeconds(2)));
            return !found.isEmpty();
        });
        return found.get(0);
    }

    private static List<ConsumerRecord<String, String>> records(String bootstrap, String topic, Duration wait) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, "probe-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> out = new ArrayList<>();
            consumer.poll(wait).forEach(out::add);
            return out;
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
