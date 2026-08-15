package ru.ludwigandreas.odatafilter.validation;

import ru.ludwigandreas.odatafilter.exception.FilterValidationException;

/**
 * Programmatic hook for business rules the declarative policy (max depth, allowed
 * fields/operators/roles, max page size) cannot express, e.g. "date range filters on Order may
 * not span more than 366 days" or "non-admins may only query their own tenant's records even
 * though tenantId itself is not filterable". Register any number of beans implementing this
 * interface; each is invoked, in bean-registration order, after the built-in checks pass and
 * before the filter is translated to a QueryDSL predicate. Throw {@link FilterValidationException}
 * (or a subclass) to reject the request with HTTP 400.
 */
@FunctionalInterface
public interface FilterValidator {

    void validate(FilterValidationContext context);
}
