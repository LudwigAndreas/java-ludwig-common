package ru.ludwigandreas.export.engine;

import com.querydsl.core.types.ExpressionUtils;
import com.querydsl.core.types.Predicate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import ru.ludwigandreas.export.api.CallIdentity;
import ru.ludwigandreas.export.api.Column;
import ru.ludwigandreas.export.api.ColumnSpec;
import ru.ludwigandreas.export.api.EnrichmentStage;
import ru.ludwigandreas.export.api.MetadataEntry;
import ru.ludwigandreas.export.api.RenderContext;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.ReportParameters;
import ru.ludwigandreas.export.api.ReportRequest;
import ru.ludwigandreas.export.api.ReportWriterFactory;
import ru.ludwigandreas.export.api.SheetDefinition;
import ru.ludwigandreas.export.api.SheetSpec;
import ru.ludwigandreas.export.api.SortKey;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.enrich.EnrichmentSettings;
import ru.ludwigandreas.export.exception.ExportProblemCodes;
import ru.ludwigandreas.export.exception.FormatNotAllowedException;
import ru.ludwigandreas.export.exception.InvalidColumnSelectionException;
import ru.ludwigandreas.export.exception.ReportForbiddenException;
import ru.ludwigandreas.export.exception.ReportIdentityUnavailableException;
import ru.ludwigandreas.export.exception.UnknownFormatOptionException;
import ru.ludwigandreas.export.exception.UnknownReportFormatException;
import ru.ludwigandreas.export.i18n.ExportMessages;
import ru.ludwigandreas.export.registry.ReportDefinitionRegistry;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Decides what a requester actually gets, and refuses everything else.
 *
 * <h2>Order matters here</h2>
 *
 * <p>The checks run outermost-first: may they run this report at all, then which columns may they
 * see, then which of those they asked for, then which formats. A planner that validated the column
 * subset before the report-level authority would tell somebody which columns a report they cannot
 * run happens to have.
 *
 * <p>Every refusal names what <em>would</em> have worked - the allowed formats, the visible columns,
 * the sortable columns - because the useful response to all of them is to retry with something valid,
 * and a client cannot get that out of a translated sentence.
 *
 * <h2>What this class does not do</h2>
 *
 * <p>It does not parse a filter and it does not know what a data scope is. Both are seams
 * ({@link ReportFilterParser}, {@link ReportScopeResolver}) whose defaults refuse and narrow nothing
 * respectively, and whose real implementations come from {@code odata-filter} and {@code security}.
 * Keeping them out means this class has no reason to grow a second filter dialect, which is the way
 * a reporting module usually acquires one.
 */
public class DefaultExecutionPlanner implements ExecutionPlanner {

    private final ReportDefinitionRegistry registry;
    private final Map<String, ReportWriterFactory> factories;
    private final ReportParameterBinder parameters;
    private final ReportScopeResolver scopes;
    private final ReportFilterParser filters;
    private final ExportMessages messages;
    private final ExportProperties properties;

    /**
     * Resolves a stage's declared identity against the module default.
     *
     * <p>Built from the same properties object, so the planner and the enrichment executor cannot
     * disagree about which identity a stage runs under - a disagreement that would present as a run
     * refused by one and attempted by the other.
     */
    private final EnrichmentSettings callIdentities;

    // SUPPRESS CHECKSTYLE ParameterNumber - a Spring bean assembled in one @Bean method, where every
    // argument is named by its own bean; there is no positional call site for the rule to protect.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public DefaultExecutionPlanner(ReportDefinitionRegistry registry,
                                   List<ReportWriterFactory> factories,
                                   ReportParameterBinder parameters, ReportScopeResolver scopes,
                                   ReportFilterParser filters, ExportMessages messages,
                                   ExportProperties properties) {
        this.registry = registry;
        this.factories = index(factories);
        this.parameters = parameters;
        this.scopes = scopes;
        this.filters = filters;
        this.messages = messages;
        this.properties = properties;
        this.callIdentities = new EnrichmentSettings(properties.getEnrichment());
    }

    @Override
    public ExecutionPlan<?, ?> plan(UUID runId, ReportRequest request, String principalId,
                                    Set<String> authorities, boolean onRequestThread) {
        ReportDefinition<?, ?> definition = registry.require(request.definitionKey());
        if (!definition.isRunnableBy(authorities)) {
            throw new ReportForbiddenException(definition.getKey());
        }
        requireAvailableCallIdentity(definition, onRequestThread);
        return planTyped(cast(definition), runId, request, principalId, authorities, onRequestThread);
    }

    /**
     * Refuses a plan whose stages need a token this attempt does not have.
     *
     * <p>Checked here rather than when the stage first calls its partner, because by then the run has
     * walked the source and written rows: the file would be abandoned anyway, and the failure would
     * arrive after the expensive part. Checking at plan time makes it free and makes the message
     * available to the request thread, which is where somebody can act on it.
     *
     * <p>Named after the first offending stage rather than reporting all of them. One is enough to
     * refuse the run, and the fix - either configure that stage's client for relay, or declare it
     * {@code SERVICE_ACCOUNT} - is per stage anyway.
     */
    private void requireAvailableCallIdentity(ReportDefinition<?, ?> definition, boolean onRequestThread) {
        if (onRequestThread) {
            return;
        }
        for (EnrichmentStage<?, ?, ?> stage : definition.getStages()) {
            if (callIdentities.callAs(stage) == CallIdentity.REQUESTER) {
                throw new ReportIdentityUnavailableException(definition.getKey(), stage.getName());
            }
        }
    }

    private <P extends ReportParameters, R> ExecutionPlan<P, R> planTyped(
            ReportDefinition<P, R> definition, UUID runId, ReportRequest request, String principalId,
            Set<String> authorities, boolean onRequestThread) {
        List<Column<R, ?>> columns = selectColumns(definition, request, authorities);
        List<SortKey> sort = resolveSort(definition, request);
        RenderContext render = new RenderContext(request.locale(), request.zone());
        List<SheetSpec> sheets = resolveSheets(definition, columns, render.locale());
        List<OutputTarget> outputs = resolveOutputs(definition, request, sheets);

        return ExecutionPlan.<P, R>builder()
                .runId(runId)
                .definition(definition)
                .parameters(parameters.bind(definition.getParameterType(), request.parameters()))
                .predicate(combine(definition, request, principalId, authorities))
                .sort(sort)
                .columns(columns)
                .sheetDefinitions(definition.getSheets())
                .sheetSpecs(sheets)
                .outputs(outputs)
                .render(render)
                .principalId(principalId)
                .authorities(authorities)
                .correlationId(request.idempotencyKey())
                .pageSize(properties.getSourcePageSize())
                .windowSize(properties.getWindowSize())
                .handoffQueueDepth(properties.getHandoffQueueDepth())
                .enrichmentCacheSize(properties.getEnrichment().getCacheSize())
                .rowCap(Math.min(definition.getMaxRows(), properties.getMaxRowsPerRun()))
                .wallClockBudget(properties.getWallClockBudget())
                .metadata(metadata(definition, request, principalId, render))
                .onRequestThread(onRequestThread)
                .build();
    }

    /**
     * The columns this file will actually have.
     *
     * <p>A column the requester may not see is not silently dropped from a subset they explicitly
     * asked for - that is a 403 naming the column. It <em>is</em> silently absent when they asked for
     * no subset at all, because "give me the report" means "give me the report as I am allowed to see
     * it". The two cases are genuinely different requests.
     */
    private <R> List<Column<R, ?>> selectColumns(ReportDefinition<?, R> definition,
                                                 ReportRequest request, Set<String> authorities) {
        List<Column<R, ?>> visible = definition.visibleColumns(authorities);
        if (request.columnIds().isEmpty()) {
            return requireNotEmpty(definition, visible);
        }
        List<String> visibleIds = visible.stream().map(Column::getId).toList();
        List<Column<R, ?>> selected = new ArrayList<>();
        for (String columnId : request.columnIds()) {
            Optional<Column<R, ?>> declared = definition.getColumns().stream()
                    .filter(column -> column.getId().equals(columnId))
                    .findFirst();
            if (declared.isEmpty()) {
                throw new InvalidColumnSelectionException(ExportProblemCodes.UNKNOWN_COLUMN,
                        ProblemStatus.INVALID, columnId, definition.columnIds());
            }
            if (!visibleIds.contains(columnId)) {
                throw new InvalidColumnSelectionException(ExportProblemCodes.COLUMN_FORBIDDEN,
                        ProblemStatus.FORBIDDEN, columnId, visibleIds);
            }
            selected.add(declared.get());
        }
        return requireNotEmpty(definition, selected);
    }

    private <R> List<Column<R, ?>> requireNotEmpty(ReportDefinition<?, ?> definition,
                                                   List<Column<R, ?>> columns) {
        if (columns.isEmpty()) {
            // Every column of this report is invisible to them, which is the same answer as not being
            // allowed to run it - and a far less confusing one than an empty file.
            throw new ReportForbiddenException(definition.getKey());
        }
        return columns;
    }

    private List<SortKey> resolveSort(ReportDefinition<?, ?> definition, ReportRequest request) {
        if (request.sort().isEmpty()) {
            return definition.getDefaultSort();
        }
        Set<String> sortable = definition.getSource().sortableColumns();
        for (SortKey key : request.sort()) {
            if (!sortable.contains(key.columnId())) {
                throw new InvalidColumnSelectionException(ExportProblemCodes.UNSORTABLE_COLUMN,
                        ProblemStatus.INVALID, key.columnId(), List.copyOf(new TreeSet<>(sortable)));
            }
        }
        return request.sort();
    }

    private <R> List<SheetSpec> resolveSheets(ReportDefinition<?, R> definition,
                                              List<Column<R, ?>> columns, Locale locale) {
        List<ColumnSpec> specs = columns.stream()
                .map(column -> new ColumnSpec(column.getId(),
                        messages.resolve(column.getHeaderKey(), locale), column.getFormat(),
                        column.getWidth(), column.getAggregate(), column.isPii()))
                .toList();
        List<SheetSpec> sheets = new ArrayList<>();
        for (SheetDefinition<R> sheet : definition.getSheets()) {
            sheets.add(new SheetSpec(sheet.id(), messages.resolve(sheet.titleKey(), locale), specs,
                    sheet.isPrimary()));
        }
        return List.copyOf(sheets);
    }

    private List<OutputTarget> resolveOutputs(ReportDefinition<?, ?> definition,
                                              ReportRequest request, List<SheetSpec> sheets) {
        List<ReportFormat> requested = request.formats().isEmpty()
                ? List.of(definition.getDefaultFormat())
                : request.formats();
        List<OutputTarget> outputs = new ArrayList<>(requested.size());
        for (ReportFormat format : requested) {
            checkFormat(definition, format);
            Map<String, String> options = request.optionsFor(format);
            checkOptions(format, options);
            outputs.add(OutputTarget.of(format, sheets, definition.getMultiSheetStrategy(), options,
                    definition.isTotalsRow()));
        }
        return outputs;
    }

    private void checkFormat(ReportDefinition<?, ?> definition, ReportFormat format) {
        if (!factories.containsKey(format.id())) {
            throw new UnknownReportFormatException(ExportProblemCodes.UNKNOWN_FORMAT, format.id(),
                    factories.keySet());
        }
        Set<String> enabled = properties.getFormats().getEnabled();
        if (!enabled.isEmpty() && !enabled.contains(format.id())) {
            throw new UnknownReportFormatException(ExportProblemCodes.FORMAT_DISABLED, format.id(),
                    enabled);
        }
        if (!definition.getAllowedFormats().contains(format)) {
            throw new FormatNotAllowedException(format.id(), definition.getAllowedFormats().stream()
                    .map(ReportFormat::id)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        }
    }

    /**
     * Rejects an option the writer does not understand, rather than ignoring it.
     *
     * <p>An ignored option is the most expensive failure in this class to diagnose: the requester
     * asked for a semicolon delimiter, got commas, and cannot tell whether the option was misspelled,
     * unsupported, or overridden by an administrator.
     */
    private void checkOptions(ReportFormat format, Map<String, String> options) {
        Set<String> supported = factories.get(format.id()).supportedOptions();
        for (String option : options.keySet()) {
            if (!supported.contains(option)) {
                throw new UnknownFormatOptionException(option, format.id(), supported);
            }
        }
    }

    /**
     * The scope predicate and the filter predicate, conjoined.
     *
     * <p>Conjoined rather than either-or, and the scope is applied whether or not a filter was
     * supplied: a filter narrows what a requester asked for, and a scope narrows what they are
     * entitled to. A report that applied only the filter would be a bulk read with the access control
     * removed by the act of asking a question.
     */
    private Optional<Predicate> combine(ReportDefinition<?, ?> definition, ReportRequest request,
                                        String principalId, Set<String> authorities) {
        Optional<Predicate> scope = scopes.scopeFor(definition, principalId, authorities);
        if (request.filter() == null || request.filter().isBlank()) {
            return scope;
        }
        Optional<Predicate> filter = filters.parse(definition, request.filter());
        if (scope.isEmpty()) {
            return filter;
        }
        if (filter.isEmpty()) {
            return scope;
        }
        return Optional.of(ExpressionUtils.allOf(scope.get(), filter.get()));
    }

    private List<MetadataEntry> metadata(ReportDefinition<?, ?> definition, ReportRequest request,
                                         String principalId, RenderContext render) {
        Locale locale = render.locale();
        List<MetadataEntry> entries = new ArrayList<>();
        entries.add(entry("ludwig.export.metadata.report", locale,
                messages.resolve(definition.getTitleKey(), locale)));
        entries.add(entry("ludwig.export.metadata.definition-version", locale,
                String.valueOf(definition.getVersion())));
        entries.add(entry("ludwig.export.metadata.requested-by", locale, principalId));
        entries.add(entry("ludwig.export.metadata.timezone", locale, render.zone().getId()));
        // Parameters are already redacted by whoever built the request; the planner never sees a
        // PII-flagged value in a form it would have to redact, which is what keeps that rule in one
        // place rather than in every layer that touches a parameter map.
        entries.add(entry("ludwig.export.metadata.parameters", locale, describe(request.parameters())));
        if (request.filter() != null && !request.filter().isBlank()) {
            entries.add(entry("ludwig.export.metadata.filter", locale, request.filter()));
        }
        return List.copyOf(entries);
    }

    private MetadataEntry entry(String labelKey, Locale locale, String value) {
        return new MetadataEntry(messages.resolve(labelKey, locale), value);
    }

    private String describe(Map<String, String> values) {
        if (values.isEmpty()) {
            return "";
        }
        return values.entrySet().stream()
                .map(parameter -> parameter.getKey() + "=" + parameter.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    @SuppressWarnings("unchecked")
    private static <P extends ReportParameters, R> ReportDefinition<P, R> cast(
            ReportDefinition<?, ?> definition) {
        return (ReportDefinition<P, R>) definition;
    }

    private static Map<String, ReportWriterFactory> index(List<ReportWriterFactory> factories) {
        Map<String, ReportWriterFactory> byId = new java.util.LinkedHashMap<>();
        factories.forEach(factory -> byId.put(factory.format().id(), factory));
        return Map.copyOf(byId);
    }

    /** The zone a request carries, for a caller that has only a string. */
    public static ZoneId zoneOf(String timeZone) {
        return timeZone == null || timeZone.isBlank() ? ZoneId.of("UTC") : ZoneId.of(timeZone);
    }
}
