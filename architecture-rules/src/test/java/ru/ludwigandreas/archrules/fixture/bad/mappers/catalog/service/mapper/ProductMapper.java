package ru.ludwigandreas.archrules.fixture.bad.mappers.catalog.service.mapper;

/** Violation: a hand-written mapper class where the convention is a MapStruct interface. */
public class ProductMapper {

    public String toDto(String entity) {
        return entity;
    }
}
