package ru.ludwigandreas.db.core.query;

import ru.ludwigandreas.odatafilter.core.ODataFilterService;
import ru.ludwigandreas.odatafilter.core.ODataQuery;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;

/**
 * {@link AdminQueryFilter} through the OData starter: filter only, so its paging options never apply here - the
 * administrative surface refuses them before a listing runs.
 */
public class ODataAdminQueryFilter implements AdminQueryFilter {

    private final ODataFilterService service;

    /**
     * Creates the filter.
     *
     * @param service the OData starter's parser
     */
    public ODataAdminQueryFilter(ODataFilterService service) {
        this.service = service;
    }

    @Override
    public ParsedFilter parse(Class<?> entity, String rawFilter) {
        if (rawFilter == null || rawFilter.isBlank()) {
            return ParsedFilter.none();
        }
        ODataQuery<?> query = service.parse(entity, ODataQueryOptions.filterOnly(rawFilter));
        return new ParsedFilter(query.predicate().orElse(null), query.filterSummary().paths(),
                query.filterSummary().operators());
    }
}
