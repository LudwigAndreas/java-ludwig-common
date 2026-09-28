package ru.ludwigandreas.archrules.fixture.good.catalog.web;

import org.springframework.web.bind.annotation.RestControllerAdvice;

import ru.ludwigandreas.archrules.fixture.good.catalog.support.ApplicationException;

/**
 * The one place exceptions are translated - and the reason controllers and listeners are forbidden
 * from catching them. All state is final: an advice class is a singleton like any other bean.
 */
@RestControllerAdvice
public class CatalogExceptionHandler {

    private final String defaultMessage = "Unexpected error";

    public String handle(ApplicationException exception) {
        return exception.getMessage() != null ? exception.getMessage() : defaultMessage;
    }
}
