package ru.ludwigandreas.archrules.fixture.good.catalog.repository;

import org.springframework.data.repository.CrudRepository;

import ru.ludwigandreas.archrules.fixture.good.catalog.repository.entity.ProductEntity;

/** Spring Data repository: an interface, in the repository package. */
public interface ProductRepository extends CrudRepository<ProductEntity, String> {
}
