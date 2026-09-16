package ru.ludwigandreas.archrules.fixture.good.catalog.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import ru.ludwigandreas.archrules.fixture.good.catalog.service.ProductService;
import ru.ludwigandreas.archrules.fixture.good.catalog.service.event.ProductCreatedEvent;

/** An entry point like a controller: it translates a message into a service call. */
@Component
public class ProductEventConsumer {

    private final ProductService service;

    public ProductEventConsumer(ProductService service) {
        this.service = service;
    }

    @KafkaListener(topics = "products")
    public void on(ProductCreatedEvent event) {
        service.create(event.code(), event.name());
    }
}
