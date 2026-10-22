package ru.ludwigandreas.messaging.db.integration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import ru.ludwigandreas.messaging.consumer.ListenerContainerFactoryBuilder;

/**
 * Two listeners on two clusters, written as a service writes them against Kafka: {@code catalog} on
 * {@code internal}, which the log replaces, and {@code partner} on {@code default}, a real broker.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableKafka
public class MixedTestApplication {

    public static final String LOG_TOPIC = "it.mixed.log";
    public static final String KAFKA_TOPIC = "it.mixed.kafka";

    @Bean
    Received received() {
        return new Received();
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> catalogFactory(ListenerContainerFactoryBuilder builder) {
        return builder.forTextPayload("catalog", LOG_TOPIC);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> partnerFactory(ListenerContainerFactoryBuilder builder) {
        return builder.forTextPayload("partner", KAFKA_TOPIC);
    }

    @Bean
    Listeners listeners(Received received) {
        return new Listeners(received);
    }

    public static class Received {

        public final List<String> values = Collections.synchronizedList(new ArrayList<>());
    }

    public static class Listeners {

        private final Received received;

        Listeners(Received received) {
            this.received = received;
        }

        @KafkaListener(topics = LOG_TOPIC, groupId = "${it.group:it-mixed}", containerFactory = "catalogFactory")
        public void onCatalog(ConsumerRecord<String, String> record) {
            received.values.add(record.topic() + ":" + record.value());
        }

        @KafkaListener(topics = KAFKA_TOPIC, groupId = "${it.group:it-mixed}-partner",
                containerFactory = "partnerFactory")
        public void onPartner(ConsumerRecord<String, String> record) {
            received.values.add(record.topic() + ":" + record.value());
            if ("fail".equals(record.value())) {
                throw new IllegalStateException("always fails");
            }
        }
    }
}
