package ru.ludwigandreas.archrules.fixture.bad.boundary.catalog.web.dto;

import jakarta.persistence.Entity;

/** Violation: the REST contract is also a database table. */
@Entity
public class ProductDto {

    public String code;
}
