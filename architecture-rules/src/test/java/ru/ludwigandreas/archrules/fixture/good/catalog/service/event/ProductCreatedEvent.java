package ru.ludwigandreas.archrules.fixture.good.catalog.service.event;

/** Kafka payload: its own class, free of JPA and not shared with the REST API. */
public record ProductCreatedEvent(String code, String name) {
}
