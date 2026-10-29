package ru.ludwigandreas.export.admin.service;

import com.querydsl.core.types.Predicate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import ru.ludwigandreas.db.core.query.AdminQueryFilter;
import ru.ludwigandreas.export.entity.ExportReportRun;
import ru.ludwigandreas.export.entity.ExportReportSubscription;
import ru.ludwigandreas.export.exception.UnknownReportDefinitionException;
import ru.ludwigandreas.export.lifecycle.ExportRunService;
import ru.ludwigandreas.export.repository.ExportAdminQueries;
import ru.ludwigandreas.export.repository.ExportReportRunRepository;
import ru.ludwigandreas.export.repository.ExportReportSubscriptionRepository;
import ru.ludwigandreas.export.web.dto.ReportRunResponse;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminCursor;
import ru.ludwigandreas.security.admin.AdminOutcome;
import ru.ludwigandreas.security.admin.AdminPage;
import ru.ludwigandreas.webcore.operation.OperationResponse;

/**
 * The {@code exports} area's operations: every requester's runs and every subscription, which the requester-scoped
 * API deliberately hides, and cancelling a run that should not continue.
 *
 * <p>No view carries an output or a link to one: an export's contents are its requester's, and an administrator who
 * needs them asks the requester. The run envelope is the requester's own, with its result link removed for that
 * reason.
 */
public class ExportAdminService {

    private static final String RUN = "export-run";

    private final ExportReportRunRepository runs;
    private final ExportReportSubscriptionRepository subscriptions;
    private final ExportRunService lifecycle;
    private final String basePath;
    private final AdminQueryFilter filters;
    private final AdminAudit audit;

    /**
     * Creates the service.
     *
     * @param runs          the run table
     * @param subscriptions the subscription table
     * @param lifecycle     the run lifecycle, whose cancel this reuses
     * @param basePath      the requester API's base path, which the envelope is built against
     * @param filters       parses the runs listing's {@code $filter}
     * @param audit         the administrative audit
     */
    public ExportAdminService(ExportReportRunRepository runs, ExportReportSubscriptionRepository subscriptions,
                              ExportRunService lifecycle, String basePath, AdminQueryFilter filters,
                              AdminAudit audit) {
        this.filters = filters;
        this.runs = runs;
        this.subscriptions = subscriptions;
        this.lifecycle = lifecycle;
        this.basePath = basePath;
        this.audit = audit;
    }

    /**
     * One page of runs, newest first.
     *
     * @param rawFilter an OData {@code $filter} over the run's filterable fields, or null
     * @param cursor    the previous page's cursor, or null
     * @param size      the requested page size, or null
     * @return the page
     */
    public AdminPage<RunView> runs(String rawFilter, String cursor, Integer size) {
        int pageSize = AdminCursor.pageSize(size);
        Instant afterCreatedAt = null;
        UUID afterId = null;
        if (cursor != null) {
            List<String> position = AdminCursor.decode(cursor, 2);
            afterCreatedAt = Instant.parse(position.get(0));
            afterId = UUID.fromString(position.get(1));
        }
        Predicate filter = filters.parse(ExportReportRun.class, rawFilter).where().orElse(null);
        List<RunView> rows = runs.findAll(ExportAdminQueries.runs(filter, afterCreatedAt, afterId),
                        PageRequest.of(0, pageSize + 1, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .stream().map(this::view).toList();
        return AdminPage.of(rows, pageSize, row -> AdminCursor.encode(row.createdAt().toString(), row.id().toString()));
    }

    /**
     * One run.
     *
     * @param runId the run
     * @return the run
     */
    public RunView run(UUID runId) {
        return view(require(runId));
    }

    /**
     * Requests cancellation exactly as the requester's own cancel does; cancellation is cooperative.
     *
     * @param runId the run
     * @return the run's own envelope, for the {@code 202}, and the administrative view
     */
    public Cancelled cancel(UUID runId) {
        ExportReportRun run = require(runId);
        boolean requested = lifecycle.requestCancel(runId);
        ExportReportRun now = runs.findById(runId).orElse(run);
        audit.record(RUN, runId.toString(), requested ? AdminOutcome.SUCCEEDED : AdminOutcome.REFUSED_BY_STATE,
                Map.of("requester", now.getRequester()));
        return new Cancelled(ReportRunResponse.of(now, basePath).operation(), view(now));
    }

    /**
     * Subscriptions matching the filter.
     *
     * @param owner       who created it, or null
     * @param savedReport which saved report, or null
     * @return the subscriptions
     */
    public List<SubscriptionView> subscriptions(String owner, UUID savedReport) {
        return StreamSupport.stream(subscriptions.findAll(ExportAdminQueries.subscriptions(owner, savedReport))
                        .spliterator(), false)
                .map(SubscriptionView::of).toList();
    }

    private ExportReportRun require(UUID runId) {
        return runs.findById(runId).orElseThrow(() -> new UnknownReportDefinitionException(runId.toString()));
    }

    private RunView view(ExportReportRun run) {
        OperationResponse envelope = ReportRunResponse.of(run, basePath).operation();
        OperationResponse withoutOutput = new OperationResponse(envelope.id(), envelope.status(), envelope.detail(),
                envelope.progress(), envelope.submittedAt(), envelope.startedAt(), envelope.finishedAt(), null,
                envelope.failure(), envelope.correlationId());
        return new RunView(run.getId(), run.getDefinitionKey(), run.getRequester(), run.getCreatedAt(),
                withoutOutput, run.getRowsWritten(), run.getFormats());
    }

    /**
     * One run, for an administrator.
     *
     * @param id            the run
     * @param definitionKey which report
     * @param requester     who asked for it
     * @param createdAt     when
     * @param operation     its envelope, without a result link
     * @param rowsWritten   how far it got
     * @param formats       the formats it produces
     */
    public record RunView(UUID id, String definitionKey, String requester, Instant createdAt,
                          OperationResponse operation, long rowsWritten, List<String> formats) {
    }

    /**
     * What a cancel produced.
     *
     * @param envelope the run's own envelope, which the cancellation response is built from
     * @param view     the administrative view
     */
    public record Cancelled(OperationResponse envelope, RunView view) {
    }

    /**
     * One subscription.
     *
     * @param id          the subscription
     * @param savedReport the saved report it runs
     * @param cron        its schedule
     * @param timeZone    the schedule's zone
     * @param runAs       whose authority it runs with
     * @param enabled     whether it fires
     * @param lastRunAt   when it last ran
     * @param lastRunId   its last run
     * @param createdBy   its owner
     */
    public record SubscriptionView(UUID id, UUID savedReport, String cron, String timeZone, String runAs,
                                   boolean enabled, Instant lastRunAt, UUID lastRunId, String createdBy) {

        static SubscriptionView of(ExportReportSubscription subscription) {
            return new SubscriptionView(subscription.getId(), subscription.getSavedReportId(),
                    subscription.getCron(), subscription.getTimeZone(), subscription.getRunAs(),
                    subscription.isEnabled(), subscription.getLastRunAt(), subscription.getLastRunId(),
                    subscription.getCreatedBy());
        }
    }
}
