package ru.ludwigandreas.archrules.fixture.good.catalog.service.model;

import ru.ludwigandreas.archrules.fixture.good.catalog.domain.ProductCode;

/** Service layer model. */
public record Product(ProductCode code, String name) {
}
