package ru.ludwigandreas.archrules.fixture.bad.contracts.catalog.messaging;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Violation: publishes straight through a KafkaTemplate, outside the shared contract. */
@Component
public class AdHocProducer {

    private final KafkaTemplate<String, String> template;

    public AdHocProducer(KafkaTemplate<String, String> template) {
        this.template = template;
    }

    public void send(String payload) {
        template.send("orders", payload);
    }
}
