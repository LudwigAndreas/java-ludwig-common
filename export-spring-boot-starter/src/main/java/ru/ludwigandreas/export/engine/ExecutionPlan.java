package ru.ludwigandreas.export.engine;

import com.querydsl.core.types.Predicate;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import ru.ludwigandreas.export.api.Column;
import ru.ludwigandreas.export.api.MetadataEntry;
import ru.ludwigandreas.export.api.RenderContext;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.api.ReportParameters;
import ru.ludwigandreas.export.api.SheetDefinition;
import ru.ludwigandreas.export.api.SheetSpec;
import ru.ludwigandreas.export.api.SortKey;
import ru.ludwigandreas.export.api.SourceContext;

/**
 * Everything settled before a run starts: what to read, what to write, and what it may cost.
 *
 * <h2>Why the plan is a separate object from the request</h2>
 *
 * <p>A {@code ReportRequest} is what a caller sent - strings, ids, a filter expression. A plan is
 * what that means for this definition, this requester and this configuration: typed parameters, a
 * scope predicate, the columns that survived the requester's authorities, resolved sheet titles,
 * which files will be produced and what each of them is called.
 *
 * <p>Keeping the two apart is what makes the engine testable without a security context or a web
 * layer, and it is also where the security guarantee lives. There is no path into the engine that
 * does not go through a plan, and a plan cannot be built without a predicate and an authority set -
 * so "a report that forgot to apply the data scope" is not a bug that can be written here, only one
 * that could be written in the planner, where there is one of it.
 *
 * <p>Built once per attempt, not once per run. A retry re-plans, which is what re-resolves the
 * requester's authorities at execution time rather than trusting the snapshot taken when the run was
 * accepted.
 *
 * @param <P> the definition's parameter type
 * @param <R> the row type
 */
@Getter
public final class ExecutionPlan<P extends ReportParameters, R> {

    /** The run this plan is for. */
    private final UUID runId;

    /** What is being produced. */
    private final ReportDefinition<P, R> definition;

    /** The typed, already-validated parameters. */
    private final P parameters;

    /** The conjunction of the requester's data scope and their parsed filter. */
    private final Optional<Predicate> predicate;

    /** The order the source reads in, already checked against what it can order by. */
    private final List<SortKey> sort;

    /** The columns that survived the requester's authorities, in definition order. */
    private final List<Column<R, ?>> columns;

    /** The definition's sheets, which is what routes a row to one of them. */
    private final List<SheetDefinition<R>> sheetDefinitions;

    /**
     * The same sheets as the writers see them: resolved titles, visible columns, primary flag.
     *
     * <p>Two lists rather than one because they are read by two different things for two different
     * reasons. Routing needs the discriminator, which is a function over the row type and therefore
     * generic; writing needs resolved text, which is not. Pairing them by id keeps the row type out
     * of every writer signature in the module.
     */
    private final List<SheetSpec> sheetSpecs;

    /** The files this run produces, one per format, each fully decided. */
    private final List<OutputTarget> outputs;

    /** The run's locale and timezone. */
    private final RenderContext render;

    /** Who asked, for the source's own audit and for the metadata sheet. */
    private final String principalId;

    /** The requester's authorities, <em>as re-resolved at execution time</em>. */
    private final Set<String> authorities;

    /** Ties this run's logs to its partner calls. */
    private final String correlationId;

    /** Rows to read per keyset page. */
    private final int pageSize;

    /** Rows enriched and handed to the writers as one unit. */
    private final int windowSize;

    /** Finished windows that may wait for the writer; the pipeline's backpressure. */
    private final int handoffQueueDepth;

    /** Entry ceiling for this run's enrichment cache, across every stage. */
    private final int enrichmentCacheSize;

    /**
     * The row ceiling actually in force: the lower of the definition's {@code maxRows} and the
     * estate-wide {@code max-rows-per-run}.
     */
    private final long rowCap;

    /** How long this run may take before it is failed. */
    private final Duration wallClockBudget;

    /**
     * Whether this attempt is running on the thread that made the request.
     *
     * <p>The only thing it decides is whether a stage may relay the requester's own token: that token
     * lives in the security context of the request thread and is deliberately never stored, so a
     * deferred attempt has none. Carried on the plan rather than read from the security context by
     * whoever needs it, because "is there a token in scope" and "is this attempt allowed to use one"
     * are different questions and conflating them is how a poller thread ends up calling a partner
     * anonymously.
     */
    private final boolean onRequestThread;

    /** The run's provenance, labels resolved and PII-redacted, in presentation order. */
    private final List<MetadataEntry> metadata;

    // SUPPRESS CHECKSTYLE ParameterNumber - the canonical constructor behind @Builder. Nothing calls
    // it positionally, because the builder is the only way in.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Builder
    private ExecutionPlan(UUID runId, ReportDefinition<P, R> definition, P parameters,
                          Optional<Predicate> predicate, List<SortKey> sort, List<Column<R, ?>> columns,
                          List<SheetDefinition<R>> sheetDefinitions, List<SheetSpec> sheetSpecs,
                          List<OutputTarget> outputs,
                          RenderContext render, String principalId, Set<String> authorities,
                          String correlationId, int pageSize, int windowSize, Integer handoffQueueDepth,
                          Integer enrichmentCacheSize, long rowCap,
                          Duration wallClockBudget, List<MetadataEntry> metadata,
                          boolean onRequestThread) {
        this.runId = require(runId, "a run id");
        this.definition = require(definition, "a definition");
        this.parameters = parameters;
        this.predicate = predicate == null ? Optional.empty() : predicate;
        this.sort = sort == null ? List.of() : List.copyOf(sort);
        this.columns = List.copyOf(requireNotEmpty(columns, "visible columns"));
        this.sheetDefinitions = List.copyOf(requireNotEmpty(sheetDefinitions, "sheets"));
        this.sheetSpecs = List.copyOf(requireNotEmpty(sheetSpecs, "resolved sheet specs"));
        if (this.sheetSpecs.size() != this.sheetDefinitions.size()) {
            throw new IllegalArgumentException(
                    "An ExecutionPlan has " + this.sheetDefinitions.size() + " sheets but "
                            + this.sheetSpecs.size() + " resolved specs");
        }
        this.outputs = List.copyOf(requireNotEmpty(outputs, "outputs"));
        this.render = require(render, "a render context");
        this.principalId = principalId;
        this.authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
        this.correlationId = correlationId;
        this.pageSize = requirePositive(pageSize, "page size");
        this.windowSize = requirePositive(windowSize, "window size");
        this.handoffQueueDepth = handoffQueueDepth == null ? 1 : requirePositive(handoffQueueDepth,
                "hand-off queue depth");
        this.enrichmentCacheSize = enrichmentCacheSize == null ? 1 : requirePositive(
                enrichmentCacheSize, "enrichment cache size");
        this.rowCap = rowCap < 1 ? Long.MAX_VALUE : rowCap;
        this.wallClockBudget = require(wallClockBudget, "a wall-clock budget");
        this.metadata = metadata == null ? List.of() : List.copyOf(metadata);
        this.onRequestThread = onRequestThread;
    }

    /** The context the source is opened with. */
    public SourceContext<P> sourceContext() {
        return new SourceContext<>(runId, parameters, predicate, sort, pageSize, render.locale(),
                render.zone(), principalId, authorities, correlationId);
    }

    /** Every sheet id this run writes to at least one output. */
    public List<String> sheetIds() {
        return sheetDefinitions.stream().map(SheetDefinition::id).toList();
    }

    @Override
    public String toString() {
        return "ExecutionPlan(" + definition.getKey() + " run " + runId + ", " + columns.size()
                + " columns, " + outputs.size() + " output(s))";
    }

    private static <T> T require(T value, String what) {
        if (value == null) {
            throw new IllegalArgumentException("An ExecutionPlan needs " + what);
        }
        return value;
    }

    private static <T> List<T> requireNotEmpty(List<T> value, String what) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("An ExecutionPlan needs " + what);
        }
        return value;
    }

    private static int requirePositive(int value, String what) {
        if (value < 1) {
            throw new IllegalArgumentException("ExecutionPlan " + what + " must be at least 1, was: " + value);
        }
        return value;
    }
}
