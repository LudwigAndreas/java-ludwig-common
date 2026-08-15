package ru.ludwigandreas.odatafilter.exception;

/**
 * The referenced property does not exist, is not annotated {@code @Filterable}, is used with an
 * operator it does not allow, or its association path exceeds the configured nesting limit.
 * Maps to HTTP 400 - from the caller's point of view this is indistinguishable from "unknown
 * field", which is intentional: it does not leak which entity fields exist but are off-limits.
 */
public class UnfilterableFieldException extends ODataFilterException {

    private final String propertyPath;

    public UnfilterableFieldException(String propertyPath, String reason) {
        super("Property '%s' cannot be used in $filter/$orderby: %s".formatted(propertyPath, reason));
        this.propertyPath = propertyPath;
    }

    public String propertyPath() {
        return propertyPath;
    }
}
