package ru.ludwigandreas.archrules.fixture.good.catalog.web.dto;

/** REST contract: neither an entity nor a Kafka payload. */
public record ProductResponse(String code, String name) {
}
