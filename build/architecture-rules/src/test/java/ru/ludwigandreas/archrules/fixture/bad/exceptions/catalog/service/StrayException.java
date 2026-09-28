package ru.ludwigandreas.archrules.fixture.bad.exceptions.catalog.service;

/** Violation: an exception outside the shared hierarchy, invisible to the central advice. */
public class StrayException extends RuntimeException {

    public StrayException(String message) {
        super(message);
    }
}
