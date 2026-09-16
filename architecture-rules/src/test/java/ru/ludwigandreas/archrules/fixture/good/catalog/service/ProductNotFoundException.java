package ru.ludwigandreas.archrules.fixture.good.catalog.service;

import ru.ludwigandreas.archrules.fixture.good.catalog.support.ApplicationException;

/** A custom exception, extending the shared base like every other one. */
public class ProductNotFoundException extends ApplicationException {

    public ProductNotFoundException(String code) {
        super("No product " + code);
    }
}
