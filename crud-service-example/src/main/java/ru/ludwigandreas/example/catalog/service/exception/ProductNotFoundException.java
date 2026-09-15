package ru.ludwigandreas.example.catalog.service.exception;

import java.util.UUID;
import ru.ludwigandreas.example.catalog.support.i18n.LocalizedException;
import ru.ludwigandreas.example.catalog.support.i18n.ProblemStatus;

/** No product with the requested id exists. Rendered as HTTP 404. */
public class ProductNotFoundException extends LocalizedException {

    public ProductNotFoundException(UUID id) {
        super(ProblemStatus.NOT_FOUND, "error.product.not-found", id);
    }
}
