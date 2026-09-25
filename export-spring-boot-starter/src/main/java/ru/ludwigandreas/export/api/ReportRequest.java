package ru.ludwigandreas.export.api;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What a requester asked for, as it stands after the edge has parsed it and before the engine has
 * resolved it.
 *
 * <p>Parameters are still a map here. That is the one place in this module where they are: they
 * arrive from HTTP or from a saved configuration as strings, and the engine binds them to the
 * definition's {@link ReportParameters} record and validates them before a run row is written. A
 * request that reached the engine with typed parameters would have had to be constructed by
 * something that already knew the definition, which the web layer deliberately does not.
 *
 * <h2>The idempotency key is what makes restart-from-scratch safe</h2>
 *
 * <p>A failed run is retried by restarting it, not by resuming it (see {@link RunStatus}). That is
 * only correct if running the same request twice produces the same file, which is what this key
 * asserts: the engine derives it from the hash of the whole request when the caller does not supply
 * one, and refuses a second run under a key that already has one in flight. A caller that supplies
 * its own gets the same protection across retries of its own HTTP call.
 *
 * @param definitionKey  which report
 * @param parameters     raw parameter values, bound and validated against the definition's type
 * @param columnIds      the column subset asked for; empty means every column the requester may see
 * @param filter         an OData {@code $filter} expression, parsed by {@code odata-filter} against
 *                       the definition's filterable columns; null means no filter
 * @param sort           the requested order; empty means the definition's default
 * @param formats        the formats to produce. More than one is a single pass over the rows into
 *                       several writers, because the enrichment calls dominate the cost and a second
 *                       pass would pay them twice
 * @param options        per-format options, keyed by format id then option name; validated against
 *                       the chosen writer's {@link ReportWriterFactory#supportedOptions()}
 * @param locale         the caller's locale
 * @param zone           the timezone instants are presented in
 * @param savedReportId  the saved configuration this came from, or null for an ad-hoc request
 * @param idempotencyKey the caller's own key, or null to have one derived from the request
 */
public record ReportRequest(
        String definitionKey,
        Map<String, String> parameters,
        List<String> columnIds,
        String filter,
        List<SortKey> sort,
        List<ReportFormat> formats,
        Map<String, Map<String, String>> options,
        Locale locale,
        ZoneId zone,
        UUID savedReportId,
        String idempotencyKey) {

    // SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor. Every component is
    // named at the call site by construction, which is the readability the rule protects.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReportRequest {
        if (definitionKey == null || definitionKey.isBlank()) {
            throw new IllegalArgumentException("A ReportRequest needs a definition key");
        }
        if (locale == null || zone == null) {
            throw new IllegalArgumentException("A ReportRequest needs both a locale and a zone");
        }
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        columnIds = columnIds == null ? List.of() : List.copyOf(columnIds);
        sort = sort == null ? List.of() : List.copyOf(sort);
        formats = formats == null ? List.of() : List.copyOf(formats);
        options = options == null ? Map.of() : deepCopy(options);
    }

    /** The options the request carries for one format, empty when it carries none. */
    public Map<String, String> optionsFor(ReportFormat format) {
        return options.getOrDefault(format.id(), Map.of());
    }

    private static Map<String, Map<String, String>> deepCopy(Map<String, Map<String, String>> source) {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        source.forEach((formatId, values) -> copy.put(formatId, values == null ? Map.of() : Map.copyOf(values)));
        return Map.copyOf(copy);
    }
}
