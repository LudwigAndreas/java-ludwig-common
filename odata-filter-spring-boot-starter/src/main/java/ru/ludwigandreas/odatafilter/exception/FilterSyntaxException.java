package ru.ludwigandreas.odatafilter.exception;

/** The raw {@code $filter}/{@code $orderby}/{@code $top}/{@code $skip} value is not well-formed. Maps to HTTP 400. */
public class FilterSyntaxException extends ODataFilterException {

    public FilterSyntaxException(String message) {
        super(message);
    }

    public FilterSyntaxException(String message, Throwable cause) {
        super(message, cause);
    }
}
