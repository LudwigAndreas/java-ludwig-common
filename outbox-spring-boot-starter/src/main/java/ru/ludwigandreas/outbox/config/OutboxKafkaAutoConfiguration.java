package ru.ludwigandreas.outbox.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import ru.ludwigandreas.outbox.dispatch.OutboxDispatcher;
import ru.ludwigandreas.outbox.dispatch.kafka.KafkaOutboxDispatcher;

import java.util.Map;

/**
 * Builds its own dedicated {@code KafkaTemplate<String, String>} (rather than depending on the
 * consumer's own {@code KafkaTemplate} bean, whose generic type Spring Boot defaults to
 * {@code <Object, Object>}) so String key/value (de)serialization is guaranteed regardless of how the
 * consuming application configures its own Kafka producer.
 */
@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxKafkaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "outboxKafkaTemplate")
    public KafkaTemplate<String, String> outboxKafkaTemplate(KafkaProperties kafkaProperties) {
        Map<String, Object> producerProps = kafkaProperties.buildProducerProperties(null);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
    }

    @Bean
    @ConditionalOnMissingBean(name = "kafkaOutboxDispatcher")
    public OutboxDispatcher kafkaOutboxDispatcher(@Qualifier("outboxKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                                                   OutboxProperties properties) {
        return new KafkaOutboxDispatcher(kafkaTemplate, properties.getKafka().getSendTimeout());
    }
}
