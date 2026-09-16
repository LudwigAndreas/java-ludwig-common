package ru.ludwigandreas.archrules.fixture.good.catalog.service;

import ru.ludwigandreas.archrules.fixture.good.catalog.service.event.ProductCreatedEvent;

/** Outbound port; the Kafka adapter implements it, so the service never sees Kafka. */
public interface ProductEventPublisher {

    void publish(ProductCreatedEvent event);
}
