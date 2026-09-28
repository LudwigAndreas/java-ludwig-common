package ru.ludwigandreas.odatafilter.exception;

/**
 * The property exists and is filterable in principle, but the caller lacks a role required by
 * its {@code @Filterable(roles = ...)}. Deliberately distinct from {@link UnfilterableFieldException}
 * (400) so applications can audit/alert on 403s without conflating them with client typos.
 * Maps to HTTP 403.
 */
public class FilterAccessDeniedException extends ODataFilterException {

    private final String propertyPath;

    public FilterAccessDeniedException(String propertyPath) {
        super("Caller is not permitted to filter/sort on property '%s'".formatted(propertyPath));
        this.propertyPath = propertyPath;
    }

    public String propertyPath() {
        return propertyPath;
    }
}
