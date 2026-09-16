package ru.ludwigandreas.archrules.fixture.bad.broker.catalog.service.event;

import jakarta.persistence.Entity;

import ru.ludwigandreas.archrules.fixture.bad.broker.catalog.web.dto.OrderDto;

/** Violations: the payload is a JPA entity and reuses the REST DTO. */
@Entity
public class OrderEvent {

    public OrderDto order;
}
