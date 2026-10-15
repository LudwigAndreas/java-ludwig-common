package ru.ludwigandreas.archrules.fixture.bad.grpc.catalog.repository;

import org.springframework.stereotype.Repository;

/** A repository, recognised by its stereotype. */
@Repository
public class ProductRepository {

    public String nameOf(String id) {
        return id;
    }
}
