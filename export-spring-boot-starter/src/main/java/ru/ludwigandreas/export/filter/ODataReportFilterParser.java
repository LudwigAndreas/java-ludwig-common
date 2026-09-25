package ru.ludwigandreas.export.filter;

import com.querydsl.core.types.Predicate;
import java.util.Optional;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.engine.ReportFilterParser;
import ru.ludwigandreas.odatafilter.core.ODataFilterService;
import ru.ludwigandreas.odatafilter.core.ODataQuery;

/**
 * Parses a requester's {@code $filter} with {@code odata-filter-spring-boot-starter}.
 *
 * <p>There is no parsing code here and there never will be. The platform has one filter dialect, one
 * set of {@code @Filterable} annotations saying which properties a caller may name, and one
 * {@code FilterPolicy} deciding which of those a given caller may use - and a reporting module that
 * grew its own would give an estate two answers to "can I filter on this field", differing in
 * exactly the cases that matter.
 *
 * <p>Paging is deliberately not passed through. {@code $top} and {@code $skip} are how a screen asks
 * for a page; a report is the whole result by definition, and honouring them would produce a file
 * that silently covers a window of the data rather than the data. A requester who wants less asks
 * for it in the filter.
 *
 * <p>Ordering is not passed through either: a report's order is the definition's or the request's
 * {@code sort}, which is validated against what the {@code RowSource} can actually order by. An
 * {@code $orderby} accepted here would be a second, unvalidated way to set it, and a source that
 * ignored it would break keyset pagination silently.
 */
public class ODataReportFilterParser implements ReportFilterParser {

    private final ODataFilterService filters;

    public ODataReportFilterParser(ODataFilterService filters) {
        this.filters = filters;
    }

    @Override
    public Optional<Predicate> parse(ReportDefinition<?, ?> definition, String filter) {
        Class<?> entityType = definition.getFilterEntityType();
        if (entityType == null) {
            // The registry refuses a definition with filterable columns and no entity, so reaching
            // here means the request carried a filter for a report that declares none at all.
            throw new ReportNotFilterableException(definition.getKey());
        }
        ODataQuery<?> query = filters.parse(entityType, filter, null, null, null);
        return Optional.ofNullable(query.predicate());
    }
}
