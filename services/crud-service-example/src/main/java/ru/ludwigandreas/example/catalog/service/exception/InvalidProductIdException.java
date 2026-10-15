package ru.ludwigandreas.example.catalog.service.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A product id that is not a UUID. Over REST the path variable's type rejects it before a handler
 * runs; over gRPC the id arrives as a string, and this is the same 400.
 */
public class InvalidProductIdException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param id the value that is not a UUID
     */
    public InvalidProductIdException(String id) {
        super(ProblemStatus.INVALID, "error.product.invalid-id", id);
    }
}
