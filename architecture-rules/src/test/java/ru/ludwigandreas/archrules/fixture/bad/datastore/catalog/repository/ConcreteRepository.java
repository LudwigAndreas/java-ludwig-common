package ru.ludwigandreas.archrules.fixture.bad.datastore.catalog.repository;

import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

/** Violation: a Spring Data repository implemented as a class. */
public class ConcreteRepository implements CrudRepository<StrayRecord, String> {

    @Override
    public <S extends StrayRecord> S save(S entity) {
        return entity;
    }

    @Override
    public Optional<StrayRecord> findById(String id) {
        return Optional.empty();
    }
}
