package ru.ludwigandreas.export.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The body of {@code POST /api/v1/reports/{definitionKey}/runs}.
 *
 * <p>Parameters arrive as strings and stay strings until the planner binds them to the definition's
 * typed parameter record. That is the one place in the module where they are untyped, and it is
 * deliberate: a DTO that tried to be typed would have to be generated per definition, and a request
 * that arrived as JSON numbers and dates would bind by Jackson's rules rather than by the ones every
 * other endpoint in the service uses.
 *
 * @param parameters    values for the definition's parameters
 * @param columnIds     the columns to include; empty means every column the requester may see
 * @param filter        an OData {@code $filter}, parsed against the definition's filterable columns
 * @param sort          the order, validated against what the row source can order by
 * @param formats       the formats to produce; empty means the definition's default. More than one
 *                      is a single pass over the rows, because the enrichment calls dominate the cost
 * @param options       per-format options, keyed by format id then option name
 * @param locale        the caller's locale; the {@code Accept-Language} header when absent
 * @param timeZone      the zone instants are presented in; UTC when absent
 * @param savedReportId the configuration this came from, for the trail
 */
@Schema(description = "A request to produce a report")
public record RunReportRequest(
        Map<String, String> parameters,
        @Size(max = 200) List<String> columnIds,
        @Size(max = 4_000) String filter,
        @Size(max = 10) List<SortRequest> sort,
        @Size(max = 5) List<String> formats,
        Map<String, Map<String, String>> options,
        String locale,
        String timeZone,
        UUID savedReportId) {

    public RunReportRequest {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        columnIds = columnIds == null ? List.of() : List.copyOf(columnIds);
        sort = sort == null ? List.of() : List.copyOf(sort);
        formats = formats == null ? List.of() : List.copyOf(formats);
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /**
     * One sort term.
     *
     * @param columnId  the column
     * @param direction {@code asc} or {@code desc}; ascending when absent
     */
    @Schema(description = "One term of the requested order")
    public record SortRequest(String columnId, String direction) {
    }
}
