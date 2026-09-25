package ru.ludwigandreas.export.lifecycle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.export.api.CallIdentity;
import ru.ludwigandreas.export.api.Column;
import ru.ludwigandreas.export.api.EnrichmentStage;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.ReportParameters;
import ru.ludwigandreas.export.api.ReportRequest;
import ru.ludwigandreas.export.api.RunStatus;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.engine.ExecutionPlan;
import ru.ludwigandreas.export.engine.ExecutionPlanner;
import ru.ludwigandreas.export.engine.ReportRunEngine;
import ru.ludwigandreas.export.engine.ReportRunResult;
import ru.ludwigandreas.export.engine.RunCancellation;
import ru.ludwigandreas.export.enrich.EnrichmentSettings;
import ru.ludwigandreas.export.entity.ExportReportRun;
import ru.ludwigandreas.export.exception.ExportException;
import ru.ludwigandreas.export.exception.ReportForbiddenException;
import ru.ludwigandreas.export.exception.ReportIdentityUnavailableException;
import ru.ludwigandreas.export.registry.ReportDefinitionRegistry;
import ru.ludwigandreas.export.security.ReportAuthorities;

/**
 * Accepts a report request and decides whether the caller waits for it.
 *
 * <h2>One code path, two exits</h2>
 *
 * <p>A small report runs on the request thread; a large one becomes a row the poller claims. What
 * does <em>not</em> differ is the engine, the plan, the scope, the limits or the audit - the only
 * thing the threshold decides is who is holding the thread while the file is written. Forking the
 * engine at this point is the obvious shortcut and it is how two code paths come to disagree about
 * what a report contains, which is a defect nobody finds because only one of the two is ever
 * exercised by a test.
 *
 * <h2>The threshold is a hint; the limits are not</h2>
 *
 * <p>{@code sync-threshold-rows} is compared against {@code RowSource.estimateRows}, which is allowed
 * to be an estimate and allowed to decline. A source that cannot answer cheaply returns
 * {@code UNKNOWN_ROW_COUNT} and the run is deferred, which is the safe direction to be wrong in. The
 * row cap and the wall-clock budget are enforced during the run against real counts, so a bad
 * estimate costs a scheduling decision and never a runaway report.
 *
 * <h2>Where the idempotency key comes from</h2>
 *
 * <p>The caller's, when they supplied one; otherwise a hash of the request. Either way it is unique
 * in the table, which is what makes "a retry restarts the run from scratch" safe: the same request
 * cannot be in flight twice, so restarting one is not a race with another copy of it.
 */
@Slf4j
public class ReportRequestService {

    private final ReportDefinitionRegistry registry;
    private final ExecutionPlanner planner;
    private final ReportRunEngine engine;
    private final ExportRunService lifecycle;
    private final ReportAuthorities authorities;
    private final ExportProperties properties;

    /** Resolves a stage's declared identity against the module default; see {@link CallIdentity}. */
    private final EnrichmentSettings callIdentities;

    // SUPPRESS CHECKSTYLE ParameterNumber - a Spring bean assembled in one @Bean method, where every
    // argument is named by its own bean; there is no positional call site for the rule to protect.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReportRequestService(ReportDefinitionRegistry registry, ExecutionPlanner planner,
                                ReportRunEngine engine, ExportRunService lifecycle,
                                ReportAuthorities authorities, ExportProperties properties) {
        this.registry = registry;
        this.planner = planner;
        this.engine = engine;
        this.lifecycle = lifecycle;
        this.authorities = authorities;
        this.properties = properties;
        this.callIdentities = new EnrichmentSettings(properties.getEnrichment());
    }

    /**
     * Accepts a request.
     *
     * @param request     what was asked for
     * @param principalId who is asking
     * @return the run row, either already finished or waiting to be claimed
     */
    public ExportReportRun submit(ReportRequest request, String principalId) {
        Set<String> granted = authorities.forPrincipal(principalId);
        ReportDefinition<?, ?> definition = registry.require(request.definitionKey());
        if (!definition.isRunnableBy(granted)) {
            // Checked here as well as in the planner, because a refused request should never have
            // taken a row, a quota slot or an idempotency key.
            throw new ReportForbiddenException(definition.getKey());
        }
        ExportReportRun run = lifecycle.submit(toRow(request, definition, principalId, granted));
        if (run.getStatus() != RunStatus.PENDING) {
            return run;
        }
        if (!shouldRunNow(request, principalId, granted, run)) {
            requireDeferrable(definition);
            return run;
        }
        return runNow(run, request, principalId, granted);
    }

    /**
     * Refuses to defer a report that can only run under the requester's own token.
     *
     * <p>The requester's access token is never stored - a stored bearer token is a credential at rest
     * with a long life - so a deferred attempt has none to relay, and a stage that needs one cannot be
     * satisfied later. The alternatives are both worse than refusing: falling back to this service's own
     * credentials produces a file scoped to the service rather than the person, and accepting the run so
     * it can fail in ten minutes spends a retry budget to reach the same answer.
     *
     * <p>Refused at the edge, where the caller is still listening and the message can say what to do -
     * narrow the request until it fits {@code sync-threshold-rows}, or have the deployment give that
     * stage's REST client its own credentials.
     */
    private void requireDeferrable(ReportDefinition<?, ?> definition) {
        for (EnrichmentStage<?, ?, ?> stage : definition.getStages()) {
            if (callIdentities.callAs(stage) == CallIdentity.REQUESTER) {
                throw new ReportIdentityUnavailableException(definition.getKey(), stage.getName());
            }
        }
    }

    /**
     * Whether this run is small enough to produce on the caller's thread.
     *
     * <p>Planned once and asked once: the estimate needs a plan - it depends on the scope predicate
     * and the parameters - and planning twice would mean a request whose authorities changed between
     * the two got a different answer from each.
     */
    private boolean shouldRunNow(ReportRequest request, String principalId, Set<String> granted,
                                 ExportReportRun run) {
        if (properties.getSyncThresholdRows() <= 0) {
            return false;
        }
        try {
            var plan = planner.plan(run.getId(), request, principalId, granted, true);
            long estimate = estimate(plan);
            return estimate >= 0 && estimate <= properties.getSyncThresholdRows();
        } catch (ReportIdentityUnavailableException e) {
            // Cannot happen - this plan was made with onRequestThread=true - but rethrown rather than
            // swallowed with everything else, because deferring a run that needs the requester's token
            // is the one wrong answer available here: the deferred attempt would fail with the same
            // code, minutes later, after taking a retry budget.
            throw e;
        } catch (RuntimeException e) {
            // A planning failure here is the same failure the poller would hit, and it is better
            // reported by the run than swallowed into a scheduling decision. Deferring lets the
            // normal failure path record it against the row with its code and its retry budget.
            log.debug("Could not estimate report run {}; deferring it: {}", run.getId(), e.toString());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private <P extends ReportParameters, R> long estimate(ExecutionPlan<?, ?> plan) {
        var typed = (ExecutionPlan<P, R>) plan;
        return typed.getDefinition().getSource().estimateRows(typed.sourceContext());
    }

    private ExportReportRun runNow(ExportReportRun run, ReportRequest request, String principalId,
                                   Set<String> granted) {
        try {
            var plan = planner.plan(run.getId(), request, principalId, granted, true);
            ReportRunResult result = engine.execute(cast(plan), RunCancellation.NEVER);
            // The row as it now stands, not the one read before the run. Returning the argument
            // reported PENDING and zero rows for a report that had just been written, which told the
            // caller to poll for a file that was already downloadable.
            return lifecycle.recordSuccess(run.getId(), result);
        } catch (RuntimeException e) {
            // Recorded, then rethrown: the caller is waiting and gets the localized problem, and the
            // row carries the same code so a later status poll says the same thing.
            lifecycle.recordFailure(run.getId(), e);
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private static <P extends ReportParameters, R> ExecutionPlan<P, R> cast(ExecutionPlan<?, ?> plan) {
        return (ExecutionPlan<P, R>) plan;
    }

    private ExportReportRun toRow(ReportRequest request, ReportDefinition<?, ?> definition,
                                  String principalId, Set<String> granted) {
        ExportReportRun run = new ExportReportRun();
        run.setDefinitionKey(definition.getKey());
        run.setDefinitionVersion(definition.getVersion());
        run.setSavedReportId(request.savedReportId());
        run.setRequester(principalId);
        run.setPrincipalSnapshot(Map.of("subject", principalId,
                "authorities", String.join(",", granted)));
        run.setParameters(redact(request.parameters(), definition));
        run.setFilterExpression(request.filter());
        run.setColumnIds(request.columnIds());
        run.setFormats(request.formats().stream().map(ReportFormat::id).toList());
        run.setLocale(request.locale().toLanguageTag());
        run.setTimeZone(request.zone().getId());
        run.setCorrelationId(request.idempotencyKey());
        run.setIdempotencyKey(request.idempotencyKey() == null
                ? hashOf(request, principalId)
                : request.idempotencyKey());
        return run;
    }

    /**
     * The parameters as they are stored, with PII-flagged values removed.
     *
     * <p>Redacted once, here, before anything persists them - so the run row, the audit event and the
     * metadata sheet all carry the same redacted map and none of them has redaction logic of its own
     * to forget. A parameter whose name matches a PII column's id is treated as that column's value,
     * which is the convention a definition expresses by naming them the same.
     */
    private Map<String, String> redact(Map<String, String> parameters,
                                       ReportDefinition<?, ?> definition) {
        Set<String> sensitive = Set.copyOf(definition.getColumns().stream()
                .filter(Column::isPii)
                .map(Column::getId)
                .toList());
        if (sensitive.isEmpty()) {
            return parameters;
        }
        Map<String, String> redacted = new LinkedHashMap<>();
        parameters.forEach((name, value) ->
                redacted.put(name, sensitive.contains(name) ? "[redacted]" : value));
        return Map.copyOf(redacted);
    }

    /**
     * A stable key for a request nobody supplied one for.
     *
     * <p>Includes the requester: two people asking for the same report are two runs, because they may
     * be entitled to different columns and rows, and handing the second person the first person's
     * file would be a disclosure produced by a caching decision.
     */
    private String hashOf(ReportRequest request, String principalId) {
        StringBuilder material = new StringBuilder()
                .append(principalId).append('|')
                .append(request.definitionKey()).append('|')
                .append(new TreeMap<>(request.parameters())).append('|')
                .append(request.columnIds()).append('|')
                .append(request.filter()).append('|')
                .append(request.sort()).append('|')
                .append(request.formats().stream().map(ReportFormat::id).sorted().toList())
                .append('|')
                .append(request.locale().toLanguageTag()).append('|')
                .append(request.zone().getId());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new ExportException("SHA-256 is not available in this JVM", e);
        }
    }

    /** A run's id, for a caller that only needs to poll it. */
    public UUID idOf(ExportReportRun run) {
        return run.getId();
    }
}
