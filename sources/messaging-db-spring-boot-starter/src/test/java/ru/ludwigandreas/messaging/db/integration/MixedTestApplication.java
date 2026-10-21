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

/** One listener subscribed to a log topic and a Kafka topic, written as a service writes it against Kafka. */
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
    ConcurrentKafkaListenerContainerFactory<String, String> mixedFactory(ListenerContainerFactoryBuilder builder) {
        return builder.forTextPayload("mixed", LOG_TOPIC, KAFKA_TOPIC);
    }

    @Bean
    MixedListener mixedListener(Received received) {
        return new MixedListener(received);
    }

    public static class Received {

        public final List<String> values = Collections.synchronizedList(new ArrayList<>());
    }

    public static class MixedListener {

        private final Received received;

        MixedListener(Received received) {
            this.received = received;
        }

        @KafkaListener(topics = {LOG_TOPIC, KAFKA_TOPIC}, groupId = "${it.group:it-mixed}",
                containerFactory = "mixedFactory")
        public void on(ConsumerRecord<String, String> record) {
            received.values.add(record.topic() + ":" + record.value());
            if ("fail".equals(record.value())) {
                throw new IllegalStateException("always fails");
            }
        }
    }
}
