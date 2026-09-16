package ru.ludwigandreas.archrules.fixture.bad.exceptions.catalog.support;

public abstract class ApplicationException extends RuntimeException {

    protected ApplicationException(String message) {
        super(message);
    }
}
