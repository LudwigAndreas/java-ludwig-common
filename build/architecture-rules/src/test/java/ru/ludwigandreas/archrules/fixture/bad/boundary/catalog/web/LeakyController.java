package ru.ludwigandreas.archrules.fixture.bad.boundary.catalog.web;

import java.util.Optional;

import jakarta.persistence.EntityManager;
import org.springframework.web.bind.annotation.RestController;

import ru.ludwigandreas.archrules.fixture.bad.boundary.catalog.repository.ProductRepository;
import ru.ludwigandreas.archrules.fixture.bad.boundary.catalog.repository.entity.ProductEntity;

/** Violations: queries the database itself, returns an entity, and calls another controller. */
@RestController
public class LeakyController {

    private final ProductRepository repository;
    private final EntityManager entityManager;
    private final NeighbourController neighbour;

    public LeakyController(ProductRepository repository, EntityManager entityManager,
                           NeighbourController neighbour) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.neighbour = neighbour;
    }

    public Optional<ProductEntity> find(String code) {
        neighbour.ping();
        entityManager.find(ProductEntity.class, code);
        return repository.findById(code);
    }
}
