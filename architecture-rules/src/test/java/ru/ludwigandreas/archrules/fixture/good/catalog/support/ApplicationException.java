package ru.ludwigandreas.archrules.fixture.good.catalog.support;

/** The single root of the service's exception hierarchy. */
public abstract class ApplicationException extends RuntimeException {

    protected ApplicationException(String message) {
        super(message);
    }
}
