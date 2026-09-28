package ru.ludwigandreas.odatafilter.exception;

/**
 * Base type for every error this library raises while parsing, validating or translating an
 * OData query. Deliberately has no dependency on Spring MVC/HttpStatus so it can be thrown and
 * caught from non-web code paths (e.g. batch jobs re-using a saved filter); the optional web
 * module maps subtypes to HTTP responses.
 */
public abstract class ODataFilterException extends RuntimeException {

    protected ODataFilterException(String message) {
        super(message);
    }

    protected ODataFilterException(String message, Throwable cause) {
        super(message, cause);
    }
}
