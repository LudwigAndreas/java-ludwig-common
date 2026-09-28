package ru.ludwigandreas.export.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.export.api.Column;
import ru.ludwigandreas.export.api.EnrichmentStage;
import ru.ludwigandreas.export.api.FormatCapabilities;
import ru.ludwigandreas.export.api.MultiSheetStrategy;
import ru.ludwigandreas.export.api.NoParameters;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.api.ReportDefinitionSource;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.SheetDefinition;
import ru.ludwigandreas.export.api.SortKey;
import ru.ludwigandreas.export.exception.ExportConfigurationException;
import ru.ludwigandreas.export.exception.UnknownReportDefinitionException;
import ru.ludwigandreas.export.render.DefaultCellRenderers;

/**
 * Every report this service can produce, checked once before the context finishes starting.
 *
 * <p>The checks below are not a completeness exercise. Each of them is a mistake that produces a
 * <em>wrong report</em> rather than a failed one, which is the class of defect this module is most
 * afraid of: a failed run is noticed within minutes, and a report that quietly lost a column is
 * noticed when somebody makes a decision on it.
 *
 * <ul>
 *   <li><b>Duplicate keys.</b> Two sources declaring {@code catalog.orders} means which one wins
 *       depends on bean ordering, so the report a user gets depends on the order Spring happened to
 *       instantiate two beans in. Rejected with both declarations named.</li>
 *   <li><b>A column fed by an undeclared stage.</b> The column would be empty in every row, and
 *       nothing at runtime would say why - the engine would look for a stage that does not exist
 *       and find nothing to run.</li>
 *   <li><b>A sort key the source cannot order by.</b> Keyset pagination needs the order to be one
 *       the source actually applies; a sort the source ignores produces pages that overlap and skip,
 *       so the file is missing rows and has others twice.</li>
 *   <li><b>A parameter type nothing can bind.</b> Discovered otherwise by the first person to run
 *       the report, as a 500.</li>
 *   <li><b>A format that cannot carry the definition.</b> Multi-sheet into a flat format under
 *       {@link MultiSheetStrategy#REJECT}, or a row cap above what one sheet can hold in a format
 *       with no second sheet to roll over onto. Both end as a truncated file that opens.</li>
 *   <li><b>A message key missing from either bundle.</b> The column header renders as its own key
 *       for exactly the users whose locale was forgotten.</li>
 *   <li><b>A syntactically invalid authority.</b> A visibility rule naming an authority the platform
 *       can never issue hides the column from everyone, permanently and silently.</li>
 *   <li><b>A column whose type cannot produce its format.</b> Either a coercion nobody reviewed or a
 *       failure on row 700,000; see {@code DefaultCellRenderers}.</li>
 *   <li><b>Stages that depend on each other in a circle.</b> No order can run them, so the
 *       definition can never produce a file at all.</li>
 * </ul>
 *
 * <p>Every problem found is reported together rather than one per restart: somebody fixing a set of
 * definitions at deploy time should get the whole list in one pass.
 *
 * <p>The registry is immutable once built. Registering a report at runtime is not supported and is
 * not an oversight - a definition is a compile-time artifact, and a mutable registry would make
 * "which reports exist" depend on what has run so far.
 */
@Slf4j
public class ReportDefinitionRegistry {

    /**
     * Authorities are uppercase, underscore- or colon-separated, optionally prefixed.
     *
     * <p>Checked syntactically only. The registry cannot know which authorities the estate issues -
     * they come from {@code identity-provider-service} at runtime - so what it can catch is a
     * misspelling that could never match anything, such as a lowercase name or one with a space in
     * it. That is the common mistake; a plausible-looking authority that is simply not issued is
     * left to the integration test that runs the report as a user.
     */
    private static final Pattern AUTHORITY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_:.\\-]*$");

    /** The locales every shipped bundle must cover; see the {@code Translation} Checkstyle rule. */
    private static final List<Locale> REQUIRED_LOCALES = List.of(Locale.ENGLISH, Locale.forLanguageTag("ru"));

    private final Map<String, ReportDefinition<?, ?>> byKey;

    /**
     * Collects every contributed definition and runs the startup checks described above.
     *
     * @param sources         the contributing beans
     * @param formats         the registered writer factories' formats, by id, so that a definition
     *                        allowing a format nothing writes is caught here rather than at the
     *                        first request
     * @param messageKeys     resolves the header, title and placeholder keys in both locales
     */
    public ReportDefinitionRegistry(List<ReportDefinitionSource> sources,
                                    Map<String, ReportFormat> formats,
                                    MessageKeyValidator messageKeys) {
        Map<String, ReportDefinition<?, ?>> collected = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        collect(sources, collected, problems);
        for (ReportDefinition<?, ?> definition : collected.values()) {
            check(definition, formats, messageKeys, problems);
        }
        if (!problems.isEmpty()) {
            throw new ExportConfigurationException(
                    "Report definitions are not usable as declared:"
                            + problems.stream().map(p -> "\n  - " + p).reduce("", String::concat));
        }
        this.byKey = Map.copyOf(collected);
        log.info("Registered {} report definitions producing {} formats",
                byKey.size(), formats.size());
    }

    /** Every registered definition, in contribution order. */
    public Collection<ReportDefinition<?, ?>> definitions() {
        return byKey.values();
    }

    /** The definition registered under this key, if any. */
    public Optional<ReportDefinition<?, ?>> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /**
     * The definition registered under this key.
     *
     * @param key the definition key
     * @return the definition
     * @throws UnknownReportDefinitionException when nothing registered that key - which is what a
     *                                          saved configuration or a subscription that outlived
     *                                          its definition looks like
     */
    public ReportDefinition<?, ?> require(String key) {
        ReportDefinition<?, ?> definition = byKey.get(key);
        if (definition == null) {
            throw new UnknownReportDefinitionException(key);
        }
        return definition;
    }

    private void collect(List<ReportDefinitionSource> sources,
                         Map<String, ReportDefinition<?, ?>> collected,
                         List<String> problems) {
        for (ReportDefinitionSource source : sources == null ? List.<ReportDefinitionSource>of() : sources) {
            Collection<ReportDefinition<?, ?>> contributed = source.definitions();
            if (contributed == null) {
                problems.add("ReportDefinitionSource " + source.getClass().getName() + " returned null");
                continue;
            }
            for (ReportDefinition<?, ?> definition : contributed) {
                if (definition == null) {
                    problems.add("ReportDefinitionSource " + source.getClass().getName()
                            + " contributed a null definition");
                    continue;
                }
                ReportDefinition<?, ?> existing = collected.putIfAbsent(definition.getKey(), definition);
                if (existing != null && existing != definition) {
                    problems.add("definition key " + definition.getKey() + " is declared twice: "
                            + existing + " and " + definition);
                }
            }
        }
    }

    private void check(ReportDefinition<?, ?> definition, Map<String, ReportFormat> formats,
                       MessageKeyValidator messageKeys, List<String> problems) {
        checkStages(definition, problems);
        checkSort(definition, problems);
        checkParameterType(definition, problems);
        checkFormats(definition, formats, problems);
        checkAuthorities(definition, problems);
        checkColumnRendering(definition, problems);
        checkMessageKeys(definition, messageKeys, problems);
    }

    private void checkStages(ReportDefinition<?, ?> definition, List<String> problems) {
        Set<String> declared = new LinkedHashSet<>();
        for (EnrichmentStage<?, ?, ?> stage : definition.getStages()) {
            declared.add(stage.getName());
        }
        for (Column<?, ?> column : definition.getColumns()) {
            if (column.isEnriched() && !declared.contains(column.getRequiredStage())) {
                problems.add(definition.getKey() + ": column " + column.getId() + " is fed by stage '"
                        + column.getRequiredStage() + "', which the definition does not declare"
                        + " (declared: " + String.join(", ", declared) + ")");
            }
        }
        for (EnrichmentStage<?, ?, ?> stage : definition.getStages()) {
            for (String dependency : stage.getDependsOn()) {
                if (!declared.contains(dependency)) {
                    problems.add(definition.getKey() + ": stage " + stage.getName() + " depends on '"
                            + dependency + "', which the definition does not declare");
                } else if (dependency.equals(stage.getName())) {
                    problems.add(definition.getKey() + ": stage " + stage.getName() + " depends on itself");
                }
            }
        }
        checkNoDependencyCycle(definition, problems);
    }

    /**
     * Refuses stages that depend on each other in a circle.
     *
     * <p>A cycle has no valid execution order at all, so the engine cannot run the definition even
     * once - and the shape of the failure without this check is the worst kind: the ordering pass
     * would have nothing to schedule and the run would stop with a message about the engine rather
     * than about the definition. Found here by repeatedly removing every stage whose dependencies
     * are already satisfied; whatever is left over is in a cycle, and naming all of it is what tells
     * the author where to cut.
     */
    private void checkNoDependencyCycle(ReportDefinition<?, ?> definition, List<String> problems) {
        List<EnrichmentStage<?, ?, ?>> remaining = new ArrayList<>(definition.getStages());
        Set<String> satisfied = new LinkedHashSet<>();
        boolean progressed = true;
        while (progressed && !remaining.isEmpty()) {
            List<EnrichmentStage<?, ?, ?>> ready = remaining.stream()
                    .filter(stage -> satisfied.containsAll(stage.getDependsOn()))
                    .toList();
            progressed = !ready.isEmpty();
            ready.forEach(stage -> satisfied.add(stage.getName()));
            remaining.removeAll(ready);
        }
        if (!remaining.isEmpty()) {
            problems.add(definition.getKey() + ": these stages depend on each other in a cycle, so no"
                    + " order can run them: "
                    + String.join(", ", remaining.stream().map(EnrichmentStage::getName).toList()));
        }
    }

    private void checkSort(ReportDefinition<?, ?> definition, List<String> problems) {
        Set<String> sortable = definition.getSource().sortableColumns();
        List<String> columnIds = definition.columnIds();
        for (SortKey key : definition.getDefaultSort()) {
            if (!columnIds.contains(key.columnId())) {
                problems.add(definition.getKey() + ": default sort names column '" + key.columnId()
                        + "', which the definition does not declare");
            } else if (!sortable.isEmpty() && !sortable.contains(key.columnId())) {
                problems.add(definition.getKey() + ": default sort names column '" + key.columnId()
                        + "', which the row source cannot order by (sortable: "
                        + String.join(", ", sortable) + ")");
            }
        }
        for (String sortableColumn : sortable) {
            if (!columnIds.contains(sortableColumn)) {
                problems.add(definition.getKey() + ": the row source declares '" + sortableColumn
                        + "' sortable, but the definition has no such column");
            }
        }
        for (String filterable : definition.getFilterableColumns()) {
            if (!columnIds.contains(filterable)) {
                problems.add(definition.getKey() + ": '" + filterable
                        + "' is declared filterable, but the definition has no such column");
            }
        }
    }

    private void checkParameterType(ReportDefinition<?, ?> definition, List<String> problems) {
        Class<?> type = definition.getParameterType();
        if (type.equals(NoParameters.class) || type.isRecord()) {
            return;
        }
        boolean bindable = false;
        for (var constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 0) {
                bindable = true;
                break;
            }
        }
        if (!bindable) {
            problems.add(definition.getKey() + ": parameter type " + type.getName()
                    + " is neither a record nor a type with a no-argument constructor, so nothing can"
                    + " bind a request's parameters to it");
        }
    }

    private void checkFormats(ReportDefinition<?, ?> definition, Map<String, ReportFormat> formats,
                              List<String> problems) {
        for (ReportFormat format : definition.getAllowedFormats()) {
            if (!formats.containsKey(format.id())) {
                problems.add(definition.getKey() + ": allows format '" + format.id()
                        + "', for which no ReportWriterFactory is registered (registered: "
                        + String.join(", ", formats.keySet()) + ")");
                continue;
            }
            FormatCapabilities capabilities = format.capabilities();
            if (definition.isMultiSheet() && !capabilities.multiSheet()
                    && definition.getMultiSheetStrategy() == MultiSheetStrategy.REJECT) {
                problems.add(definition.getKey() + ": declares " + definition.getSheets().size()
                        + " sheets and allows '" + format.id() + "', which has none, under"
                        + " MultiSheetStrategy.REJECT - choose ZIP or PRIMARY_ONLY, or stop allowing"
                        + " that format");
            }
            if (definition.getMaxRows() > capabilities.maxRowsPerSheet() && !capabilities.multiSheet()) {
                problems.add(definition.getKey() + ": maxRows is " + definition.getMaxRows()
                        + " but format '" + format.id() + "' holds at most "
                        + capabilities.maxRowsPerSheet() + " rows and cannot roll over onto a"
                        + " continuation sheet");
            }
        }
        if (definition.isMultiSheet()) {
            long primaries = definition.getSheets().stream().filter(SheetDefinition::isPrimary).count();
            if (primaries != 1 && definition.getMultiSheetStrategy() == MultiSheetStrategy.PRIMARY_ONLY) {
                problems.add(definition.getKey() + ": MultiSheetStrategy.PRIMARY_ONLY needs exactly one"
                        + " primary sheet, found " + primaries);
            }
        }
    }

    private void checkAuthorities(ReportDefinition<?, ?> definition, List<String> problems) {
        for (String authority : definition.getRequiredAuthorities()) {
            if (!AUTHORITY_PATTERN.matcher(authority).matches()) {
                problems.add(definition.getKey() + ": required authority '" + authority
                        + "' is not a syntactically valid authority, so nobody can ever run this report");
            }
        }
        for (Column<?, ?> column : definition.getColumns()) {
            for (String authority : column.getVisibleFor()) {
                if (!AUTHORITY_PATTERN.matcher(authority).matches()) {
                    problems.add(definition.getKey() + ": column " + column.getId()
                            + " is visible only for '" + authority + "', which is not a syntactically"
                            + " valid authority, so the column is hidden from everyone");
                }
            }
        }
    }

    /**
     * Refuses a column whose value type cannot produce the cell its format promises.
     *
     * <p>The pair is where a definition goes wrong in a way nothing else notices: a {@code String}
     * declared {@code MONEY} has two possible runtime behaviours and both are bad. Coerce, and the
     * file carries a plausible wrong value that somebody sums. Throw, and the run dies on row
     * 700,000 of a report a person has been waiting twenty minutes for.
     */
    private void checkColumnRendering(ReportDefinition<?, ?> definition, List<String> problems) {
        for (Column<?, ?> column : definition.getColumns()) {
            if (column.getRenderer() != null) {
                continue;
            }
            if (!DefaultCellRenderers.supports(column.getFormat(), column.getValueType())) {
                problems.add(definition.getKey() + ": column " + column.getId() + " yields "
                        + column.getValueType().getSimpleName() + " but declares format "
                        + column.getFormat().kind() + ", which accepts "
                        + DefaultCellRenderers.describeSupported(column.getFormat())
                        + "; declare a CellRenderer if the conversion is intended");
            }
        }
    }

    private void checkMessageKeys(ReportDefinition<?, ?> definition, MessageKeyValidator messageKeys,
                                  List<String> problems) {
        requireKey(definition, definition.getTitleKey(), "title", messageKeys, problems);
        for (SheetDefinition<?> sheet : definition.getSheets()) {
            requireKey(definition, sheet.titleKey(), "sheet " + sheet.id() + " title", messageKeys, problems);
        }
        for (Column<?, ?> column : definition.getColumns()) {
            requireKey(definition, column.getHeaderKey(), "column " + column.getId() + " header",
                    messageKeys, problems);
        }
        for (EnrichmentStage<?, ?, ?> stage : definition.getStages()) {
            String placeholder = stage.getMissingPolicy().messageKey();
            if (placeholder != null) {
                requireKey(definition, placeholder, "stage " + stage.getName() + " placeholder",
                        messageKeys, problems);
            }
        }
    }

    private void requireKey(ReportDefinition<?, ?> definition, String key, String what,
                            MessageKeyValidator messageKeys, List<String> problems) {
        for (Locale locale : REQUIRED_LOCALES) {
            if (!messageKeys.resolves(key, locale)) {
                problems.add(definition.getKey() + ": " + what + " message key '" + key
                        + "' does not resolve for locale '" + locale.toLanguageTag()
                        + "'; a user in that locale would see the key instead of the text");
            }
        }
    }
}
