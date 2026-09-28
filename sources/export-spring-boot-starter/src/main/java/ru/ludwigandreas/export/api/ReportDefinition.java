package ru.ludwigandreas.export.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Singular;

/**
 * One report, as the consuming service declares it: typed, named once, and checked at startup.
 *
 * <pre>{@code
 * public static final ReportDefinition<OrderReportParameters, OrderRow> ORDERS = ReportDefinition
 *         .<OrderReportParameters, OrderRow>of("catalog.orders", OrderReportParameters.class, OrderRow.class)
 *         .titleKey("report.orders.title")
 *         .source(orderRowSource)
 *         .stage(CUSTOMER_STAGE)
 *         .column(ORDER_NUMBER)
 *         .column(CUSTOMER_NAME)
 *         .column(TOTAL)
 *         .allowedFormats(Set.of(StandardReportFormats.XLSX, StandardReportFormats.CSV))
 *         .defaultFormat(StandardReportFormats.XLSX)
 *         .maxRows(2_000_000)
 *         .build();
 * }</pre>
 *
 * <h2>Why definitions are code and not rows</h2>
 *
 * <p>This is the decision the module is built around, and it is the same one
 * {@code SettingDefinition} takes for the same reasons. The obvious way to make a reporting engine
 * reusable is to let each service declare its reports as data - a table of report names, column
 * names and SQL - and that is precisely how a reporting module becomes a second, untested query
 * layer with no type checking, no refactoring support and a SQL injection surface administered
 * through a UI. Every column is a string, every extractor is an expression nothing compiles, and
 * "which reports reference this table" is unanswerable.
 *
 * <p>Declaring them as constants moves all of that to the compiler. A column's extractor is a
 * method reference, so renaming a field on the row type is a compile error rather than an empty
 * column; the parameter type is a record with Bean Validation on it; the source is a QueryDSL query
 * the compiler checks against the generated Q-types. What stays data is the part that genuinely
 * varies per user: which parameters, which columns of the allowed set, which format - a
 * {@code SavedReport} row, validated against this definition on write and again on load.
 *
 * <p>The cost is honest: adding a report requires a deploy of the service that owns the data. In
 * exchange, no report can outlive the schema it reads, and no user-authored report can put an
 * unbounded query into a production database.
 *
 * <h2>What the registry checks</h2>
 *
 * <p>A definition is validated in isolation by this constructor - the things that are wrong on their
 * own - and against the rest of the estate by {@code ReportDefinitionRegistry}, which is where
 * duplicate keys, missing message keys, undeclared stages, unsortable sort keys and formats that
 * cannot carry the definition are caught. Both happen before the context finishes starting.
 *
 * @param <P> the parameter type, a validated {@link ReportParameters} record
 * @param <R> the row type the source yields and the columns read
 */
@Getter
@EqualsAndHashCode(of = "key")
public final class ReportDefinition<P extends ReportParameters, R> {

    /**
     * Keys are lowercase, dot-separated segments. Enforced rather than recommended because a key is
     * a wire identifier: it is the path variable a run is requested under, it is stored on every run
     * row and every saved configuration, and it is a metric tag. A deployment that allowed
     * {@code Catalog.Orders} beside {@code catalog.orders} would have two reports that every human
     * reader would call one, and two metric series to match.
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9]*([.\\-][a-z0-9]+)*$");

    private static final int MAX_KEY_LENGTH = 128;

    /** Stable identifier, unique across the whole registry. */
    private final String key;

    /**
     * Bumped by the author when the shape of the output changes.
     *
     * <p>Recorded on every run and in every metadata sheet, so that a file produced months ago can
     * be explained by the definition that produced it rather than by the one that exists now. It is
     * not used for compatibility decisions - a saved configuration is validated against the current
     * definition, not against the version it was authored under - because a stored configuration
     * that silently kept working against an older shape is exactly the drift this module refuses.
     */
    private final int version;

    /** Message key for the report's title, used in the file name and the metadata sheet. */
    private final String titleKey;

    /** The parameter type, bound and validated at the edge before a run is accepted. */
    private final Class<P> parameterType;

    /** The row type the source yields. */
    private final Class<R> rowType;

    /** Where the base rows come from. */
    private final RowSource<P, R> source;

    /** The joins against other services, in declaration order; the engine reorders by dependency. */
    private final List<EnrichmentStage<R, ?, ?>> stages;

    /** Every column this report can produce, in their natural order. */
    private final List<Column<R, ?>> columns;

    /** The sheets. Exactly one for the usual report; see {@link SheetDefinition} for the rest. */
    private final List<SheetDefinition<R>> sheets;

    /** The formats a requester may ask for. */
    private final Set<ReportFormat> allowedFormats;

    /** The format used when neither the request nor a saved configuration names one. */
    private final ReportFormat defaultFormat;

    /**
     * The most rows this report may produce, enforced during the run.
     *
     * <p>Per definition rather than only global because the answer is a property of the report: a
     * daily reconciliation extract legitimately runs to millions of rows, and an on-screen summary
     * that suddenly produces a hundred thousand is a parameter mistake somebody should be told
     * about rather than served.
     */
    private final long maxRows;

    /** What happens when a multi-sheet definition is asked for in a format with one sheet. */
    private final MultiSheetStrategy multiSheetStrategy;

    /** The order used when the request does not ask for one. */
    private final List<SortKey> defaultSort;

    /** Column ids a requester may filter on; empty means this report takes no {@code $filter}. */
    private final Set<String> filterableColumns;

    /**
     * The JPA entity a {@code $filter} is parsed against, or null when this report takes none.
     *
     * <p>Not the row type. {@link #rowType} is a projection the columns read - frequently a record
     * assembled by the query - while a filter has to become a predicate over the thing the query
     * actually selects from, and only an entity carries the {@code @Filterable} annotations that say
     * which of its properties a caller may name.
     *
     * <p>Declaring it is what makes the two sets checkable against each other at startup: a
     * definition that lists filterable columns without naming an entity to resolve them on is
     * refused, rather than producing a 500 on the first filtered request.
     */
    private final Class<?> filterEntityType;

    /**
     * The logical resource name this report's rows are scoped as, or null when they are not scoped.
     *
     * <p>A name such as {@code "order"}, not a table or a class: it is what a {@code DataScopeMapping}
     * is registered under in {@code security-spring-boot-starter}, so the policy survives a rename of
     * either. Declaring it on the definition rather than deriving it from the row type is what lets
     * two reports over the same rows carry different scopes - a customer-facing extract and an
     * internal reconciliation are the same table and not the same entitlement.
     *
     * <p>Null means the rows are not row-scoped, which is a claim about the data rather than a
     * default: the startup validator cannot tell an unscoped report from one whose author forgot, so
     * it is deliberately something somebody has to write.
     */
    private final String scopeResourceType;

    /** Authorities that may run this report at all; empty means anyone the service lets in. */
    private final Set<String> requiredAuthorities;

    /** Whether a totals row is written, for formats that can carry one. */
    private final boolean totalsRow;

    /**
     * A branding template this report is written into, by name, or null for a plain file.
     *
     * <p>On the definition rather than on the request, and a name rather than a path. Both are the
     * same decision: a template controls the letterhead of a document that leaves the organisation,
     * so which one a report uses is the report author's choice, and a request that could name a
     * file would be a request that could read one.
     *
     * <p>What the name resolves to is the template source's business, and this module's default
     * source re-reads it per run - which is what lets a branding change reach production without a
     * deploy, the whole reason template mode exists.
     */
    private final String template;

    // SUPPRESS CHECKSTYLE ParameterNumber - the canonical constructor behind @Builder. The rule
    // exists to catch call sites nobody can read; nothing calls this positionally, because the
    // builder is the only way in.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Builder
    private ReportDefinition(String key, Integer version, String titleKey, Class<P> parameterType,
                             Class<R> rowType, RowSource<P, R> source,
                             @Singular List<EnrichmentStage<R, ?, ?>> stages,
                             @Singular List<Column<R, ?>> columns,
                             @Singular List<SheetDefinition<R>> sheets,
                             Set<ReportFormat> allowedFormats, ReportFormat defaultFormat,
                             Long maxRows, MultiSheetStrategy multiSheetStrategy,
                             List<SortKey> defaultSort, Set<String> filterableColumns,
                             Set<String> requiredAuthorities, Boolean totalsRow, String template,
                             Class<?> filterEntityType, String scopeResourceType) {
        this.key = requireKey(key);
        this.version = version == null ? 1 : version;
        this.titleKey = requireText(titleKey, this.key, "a title message key");
        this.parameterType = require(parameterType, this.key, "a parameter type");
        this.rowType = require(rowType, this.key, "a row type");
        this.source = require(source, this.key, "a row source");
        this.columns = List.copyOf(requireNotEmpty(columns, this.key, "columns"));
        this.allowedFormats =
                Set.copyOf(new LinkedHashSet<>(requireNotEmpty(allowedFormats, this.key, "allowed formats")));
        this.stages = List.copyOf(stages == null ? List.<EnrichmentStage<R, ?, ?>>of() : stages);
        this.sheets = sheets == null || sheets.isEmpty()
                ? List.of(SheetDefinition.single("data", titleKey))
                : List.copyOf(sheets);
        this.defaultFormat = resolveDefaultFormat(defaultFormat);
        this.maxRows = requireMaxRows(maxRows, this.key);
        this.multiSheetStrategy = multiSheetStrategy == null ? MultiSheetStrategy.REJECT : multiSheetStrategy;
        this.defaultSort = List.copyOf(defaultSort == null ? List.<SortKey>of() : defaultSort);
        this.filterableColumns = Set.copyOf(filterableColumns == null ? Set.<String>of() : filterableColumns);
        this.requiredAuthorities =
                Set.copyOf(requiredAuthorities == null ? Set.<String>of() : requiredAuthorities);
        this.totalsRow = totalsRow == null || totalsRow;
        this.template = template;
        this.filterEntityType = filterEntityType;
        this.scopeResourceType = scopeResourceType;
        if (!this.filterableColumns.isEmpty() && filterEntityType == null) {
            throw new IllegalArgumentException("Report definition " + this.key + " declares filterable"
                    + " columns but no filterEntityType to resolve them against");
        }
        checkNoDuplicateIds();
    }

    private static <T> T require(T value, String key, String what) {
        if (value == null) {
            throw new IllegalArgumentException("Report definition " + key + " needs " + what);
        }
        return value;
    }

    private static String requireText(String value, String key, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Report definition " + key + " needs " + what);
        }
        return value;
    }

    private static <C extends Collection<?>> C requireNotEmpty(C value, String key, String what) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Report definition " + key + " declares no " + what);
        }
        return value;
    }

    private static long requireMaxRows(Long maxRows, String key) {
        if (maxRows == null) {
            return Long.MAX_VALUE;
        }
        if (maxRows < 1) {
            throw new IllegalArgumentException(
                    "Report definition " + key + " has a maxRows below 1: " + maxRows);
        }
        return maxRows;
    }

    /**
     * Entry point for the builder, so the three things a definition cannot omit are named up front.
     *
     * @param key           the definition's identifier
     * @param parameterType the validated parameter record
     * @param rowType       the row type the source yields
     * @param <P>           the parameter type
     * @param <R>           the row type
     * @return a builder still needing a title key, a source, columns and formats
     */
    public static <P extends ReportParameters, R> ReportDefinitionBuilder<P, R> of(
            String key, Class<P> parameterType, Class<R> rowType) {
        return ReportDefinition.<P, R>builder().key(key).parameterType(parameterType).rowType(rowType);
    }

    /** The column with this id, if this definition declares one. */
    public Optional<Column<R, ?>> column(String columnId) {
        return columns.stream().filter(c -> c.getId().equals(columnId)).findFirst();
    }

    /** The stage with this name, if this definition declares one. */
    public Optional<EnrichmentStage<R, ?, ?>> stage(String stageName) {
        return stages.stream().filter(s -> s.getName().equals(stageName)).findFirst();
    }

    /** Every column id, in declaration order. */
    public List<String> columnIds() {
        return columns.stream().map(Column::getId).toList();
    }

    /**
     * The columns a requester holding these authorities may see, in declaration order.
     *
     * <p>Applied once, at the start of a run, and the result is what the writer is given: there is
     * no second place where visibility is consulted, because two places is one more than can be kept
     * in agreement.
     */
    public List<Column<R, ?>> visibleColumns(Set<String> authorities) {
        return columns.stream().filter(c -> c.isVisibleTo(authorities)).toList();
    }

    /** Whether a requester holding these authorities may run this report at all. */
    public boolean isRunnableBy(Set<String> authorities) {
        if (requiredAuthorities.isEmpty()) {
            return true;
        }
        return authorities != null && requiredAuthorities.stream().anyMatch(authorities::contains);
    }

    /** Whether this definition writes more than one sheet. */
    public boolean isMultiSheet() {
        return sheets.size() > 1;
    }

    /** Never includes a parameter value; this string ends up in startup logs and problem documents. */
    @Override
    public String toString() {
        return "ReportDefinition(" + key + " v" + version + ", " + columns.size() + " columns, "
                + stages.size() + " stages)";
    }

    private String requireKey(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("A report definition needs a key");
        }
        if (candidate.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Report definition key is longer than " + MAX_KEY_LENGTH + " characters: " + candidate);
        }
        if (!KEY_PATTERN.matcher(candidate).matches()) {
            throw new IllegalArgumentException(
                    "Report definition key must be lowercase dot-separated segments (e.g. catalog.orders),"
                            + " was: " + candidate);
        }
        return candidate;
    }

    private ReportFormat resolveDefaultFormat(ReportFormat declared) {
        if (declared == null) {
            return allowedFormats.iterator().next();
        }
        if (!allowedFormats.contains(declared)) {
            throw new IllegalArgumentException(
                    "Report definition " + key + " declares default format " + declared.id()
                            + " which is not in its allowed formats");
        }
        return declared;
    }

    private void checkNoDuplicateIds() {
        List<String> duplicates = new ArrayList<>();
        Set<String> seenColumns = new LinkedHashSet<>();
        for (Column<R, ?> column : columns) {
            if (!seenColumns.add(column.getId())) {
                duplicates.add("column " + column.getId());
            }
        }
        Set<String> seenStages = new LinkedHashSet<>();
        for (EnrichmentStage<R, ?, ?> stage : stages) {
            if (!seenStages.add(stage.getName())) {
                duplicates.add("stage " + stage.getName());
            }
        }
        Set<String> seenSheets = new LinkedHashSet<>();
        for (SheetDefinition<R> sheet : sheets) {
            if (!seenSheets.add(sheet.id())) {
                duplicates.add("sheet " + sheet.id());
            }
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Report definition " + key + " declares these twice: " + String.join(", ", duplicates));
        }
    }
}
