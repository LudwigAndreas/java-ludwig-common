package ru.ludwigandreas.db.core.query;

/**
 * {@link AdminQueryFilter} in a service without the OData starter: no filter is no filter, any other is refused.
 */
public class UnavailableAdminQueryFilter implements AdminQueryFilter {

    @Override
    public ParsedFilter parse(Class<?> entity, String rawFilter) {
        if (rawFilter == null || rawFilter.isBlank()) {
            return ParsedFilter.none();
        }
        throw new AdminFilterUnavailableException();
    }
}
