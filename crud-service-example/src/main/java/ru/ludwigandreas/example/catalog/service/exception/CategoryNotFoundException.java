package ru.ludwigandreas.example.catalog.service.exception;

import java.util.UUID;
import ru.ludwigandreas.example.catalog.support.i18n.LocalizedException;
import ru.ludwigandreas.example.catalog.support.i18n.ProblemStatus;

/**
 * The request referenced a category that does not exist. Rendered as HTTP 422: the request was
 * well-formed and the product URL is valid, but the payload can't be acted on.
 */
public class CategoryNotFoundException extends LocalizedException {

    public CategoryNotFoundException(UUID id) {
        super(ProblemStatus.UNPROCESSABLE, "error.category.not-found", id);
    }
}
