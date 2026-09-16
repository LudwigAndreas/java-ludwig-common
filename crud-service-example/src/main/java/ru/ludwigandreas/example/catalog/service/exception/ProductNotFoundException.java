package ru.ludwigandreas.example.catalog.service.exception;

import java.util.UUID;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/** No product with the requested id exists. Rendered as HTTP 404. */
public class ProductNotFoundException extends LocalizedException {

    public ProductNotFoundException(UUID id) {
        super(ProblemStatus.NOT_FOUND, "error.product.not-found", id);
    }
}
