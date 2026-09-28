package ru.ludwigandreas.archrules.fixture.bad.broker.catalog.service;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/** Violation: the Kafka client outside the messaging packages. */
@Service
public class DirectKafkaService {

    private final KafkaTemplate<String, String> template;

    public DirectKafkaService(KafkaTemplate<String, String> template) {
        this.template = template;
    }

    public void publish(String payload) {
        template.send("orders", payload);
    }
}
