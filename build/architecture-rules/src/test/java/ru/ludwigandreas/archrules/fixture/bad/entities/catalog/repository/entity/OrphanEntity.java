package ru.ludwigandreas.archrules.fixture.bad.entities.catalog.repository.entity;

import jakarta.persistence.Entity;

/** Violation: its own id and timestamps, invisible to the shared auditing. */
@Entity
public class OrphanEntity {

    private String id;

    private String createdAt;

    public String getId() {
        return id;
    }

    public String getCreatedAt() {
        return createdAt;
    }
}
