package ru.ludwigandreas.archrules.fixture.bad.boundary.catalog.repository;

import org.springframework.data.repository.CrudRepository;

import ru.ludwigandreas.archrules.fixture.bad.boundary.catalog.repository.entity.ProductEntity;

public interface ProductRepository extends CrudRepository<ProductEntity, String> {
}
