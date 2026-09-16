package ru.ludwigandreas.archrules.fixture.good.catalog.messaging;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import ru.ludwigandreas.archrules.fixture.good.catalog.service.ProductEventPublisher;
import ru.ludwigandreas.archrules.fixture.good.catalog.service.event.ProductCreatedEvent;

/** The only place that knows Kafka exists, apart from the consumer next to it. */
@Component
public class KafkaProductEventPublisher implements ProductEventPublisher {

    private final KafkaTemplate<String, ProductCreatedEvent> template;

    public KafkaProductEventPublisher(KafkaTemplate<String, ProductCreatedEvent> template) {
        this.template = template;
    }

    @Override
    public void publish(ProductCreatedEvent event) {
        template.send("products", event);
    }
}
