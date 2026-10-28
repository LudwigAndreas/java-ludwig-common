package ru.ludwigandreas.archrules.fixture.bad.admin.repository;

import org.springframework.stereotype.Repository;

/** A repository, recognised by its stereotype. */
@Repository
public class ThingRepository {

    public String nameOf(String id) {
        return id;
    }
}
