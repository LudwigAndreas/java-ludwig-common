package ru.ludwigandreas.odatafilter.exception;

/** Raised by a consumer-supplied {@code FilterValidator} to reject a semantically invalid filter. Maps to HTTP 400. */
public class FilterValidationException extends ODataFilterException {

    public FilterValidationException(String message) {
        super(message);
    }
}
