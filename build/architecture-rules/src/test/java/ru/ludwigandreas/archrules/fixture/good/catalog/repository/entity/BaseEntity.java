package ru.ludwigandreas.archrules.fixture.good.catalog.repository.entity;

import jakarta.persistence.MappedSuperclass;

/**
 * The audit base class every entity inherits: id and the standard timestamps in one place.
 *
 * <p>It lives in the entity package because it is itself a persistent type - a @MappedSuperclass
 * parked outside would break the rule that places persistent types.
 */
@MappedSuperclass
public abstract class BaseEntity {

    private final String id = null;

    private final String createdAt = null;

    private final String updatedAt = null;

    public String id() {
        return id;
    }

    public String createdAt() {
        return createdAt;
    }

    public String updatedAt() {
        return updatedAt;
    }
}
