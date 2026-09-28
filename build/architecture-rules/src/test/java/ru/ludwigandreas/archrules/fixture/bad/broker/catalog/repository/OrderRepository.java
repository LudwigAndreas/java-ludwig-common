package ru.ludwigandreas.archrules.fixture.bad.broker.catalog.repository;

import org.springframework.data.repository.CrudRepository;

import ru.ludwigandreas.archrules.fixture.bad.broker.catalog.repository.entity.OrderEntity;

public interface OrderRepository extends CrudRepository<OrderEntity, String> {
}
