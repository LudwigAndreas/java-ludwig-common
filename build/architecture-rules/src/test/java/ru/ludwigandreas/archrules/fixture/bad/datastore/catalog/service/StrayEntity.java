package ru.ludwigandreas.archrules.fixture.bad.datastore.catalog.service;

import jakarta.persistence.Entity;

/** Violation: an entity outside the entity packages. */
@Entity
public class StrayEntity {

    public String id;
}
