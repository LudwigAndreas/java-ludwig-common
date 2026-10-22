package ru.ludwigandreas.asyncapi.integration;

import java.math.BigDecimal;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import ru.ludwigandreas.messaging.consumer.ListenerContainerFactoryBuilder;
import ru.ludwigandreas.outbox.api.OutboxEventTypes;

/** A service written as platform services are, whose document the shape test reads. */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableKafka
public class ShapeApplication {

    public record OrderPlaced(String id, int quantity) {
    }

    public record InvoicePayload(String number, BigDecimal amount) {
    }

    public record AuditPayload(String what) {
    }

    @Bean
    OutboxEventTypes events() {
        return OutboxEventTypes.merge(java.util.List.of(
                OutboxEventTypes.of(Map.of("InvoiceIssued", InvoicePayload.class)),
                OutboxEventTypes.routed("AuditRecorded", AuditPayload.class, "audit")));
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, OrderPlaced> ordersFactory(
            ListenerContainerFactoryBuilder builder) {
        return builder.forJsonPayload("orders-ingress", OrderPlaced.class, "partner.orders");
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> settingsFactory(ListenerContainerFactoryBuilder builder) {
        return builder.forTextPayload("settings-sync", "user.settings");
    }

    @Bean
    Listeners listeners() {
        return new Listeners();
    }

    public static class Listeners {

        @KafkaListener(topics = "partner.orders", groupId = "shape", containerFactory = "ordersFactory")
        public void orders(ConsumerRecord<String, OrderPlaced> record) {
            // documented only
        }

        @KafkaListener(topics = "${shape.settings-topic:user.settings}", groupId = "shape",
                containerFactory = "settingsFactory")
        public void settings(String value) {
            // documented only
        }
    }
}
