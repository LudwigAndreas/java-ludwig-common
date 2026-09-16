package ru.ludwigandreas.archrules.fixture.bad.entities.catalog.repository.entity;

import jakarta.persistence.MappedSuperclass;

@MappedSuperclass
public abstract class BaseEntity {

    private final String id = null;

    public String id() {
        return id;
    }
}
