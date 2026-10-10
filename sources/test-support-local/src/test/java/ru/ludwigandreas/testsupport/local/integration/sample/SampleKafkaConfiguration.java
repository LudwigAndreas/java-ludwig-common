package ru.ludwigandreas.testsupport.local.integration.sample;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The sample's one topic and its listener, behind a switch.
 *
 * <p>The switch is the realistic part. A service on this platform commonly has {@code spring-kafka} on
 * its classpath and its consumers disabled by configuration, which is exactly why the launcher names
 * Kafka explicitly and infers nothing from the classpath.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("sample.kafka.enabled")
public class SampleKafkaConfiguration {

    /** The sample's topic. */
    public static final String TOPIC = "sample.widgets";

    @Bean
    NewTopic sampleWidgetsTopic() {
        return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
    }

    @Bean
    Received received() {
        return new Received();
    }

    /** Everything the listener has been handed, in arrival order. */
    public static class Received {

        private final List<ConsumerRecord<String, String>> records = new CopyOnWriteArrayList<>();

        @KafkaListener(topics = TOPIC, groupId = "sample")
        void onRecord(ConsumerRecord<String, String> record) {
            records.add(record);
        }

        public List<ConsumerRecord<String, String>> withKey(String key) {
            return records.stream().filter(record -> key.equals(record.key())).toList();
        }
    }
}
