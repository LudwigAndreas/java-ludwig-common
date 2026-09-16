package ru.ludwigandreas.archrules.fixture.bad.broker.catalog.listener;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import ru.ludwigandreas.archrules.fixture.bad.broker.catalog.repository.OrderRepository;

/** Violations: a consumer outside the messaging packages, writing to the database itself. */
@Component
public class OrderListener {

    private final OrderRepository repository;

    public OrderListener(OrderRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = "orders")
    public void on(String id) {
        repository.findById(id);
    }
}
