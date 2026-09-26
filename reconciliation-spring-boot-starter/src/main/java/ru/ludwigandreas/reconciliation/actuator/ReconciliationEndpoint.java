package ru.ludwigandreas.reconciliation.actuator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.data.domain.PageRequest;
import ru.ludwigandreas.db.core.audit.AuditorProvider;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent;
import ru.ludwigandreas.reconciliation.audit.ReconciliationAuditEvent.Category;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.engine.RegisteredTask;
import ru.ludwigandreas.reconciliation.engine.ReconciliationRuntime;
import ru.ludwigandreas.reconciliation.engine.TaskRegistry;
import ru.ludwigandreas.reconciliation.engine.TaskRunner;
import ru.ludwigandreas.reconciliation.engine.TaskStateService;
import ru.ludwigandreas.reconciliation.entity.QuotaLease;
import ru.ludwigandreas.reconciliation.entity.SyncInboxRecord;
import ru.ludwigandreas.reconciliation.entity.SyncRecordStatus;
import ru.ludwigandreas.reconciliation.entity.RemoteJobState;
import ru.ludwigandreas.reconciliation.entity.SyncRemoteJob;
import ru.ludwigandreas.reconciliation.quota.DatabaseQuota;
import ru.ludwigandreas.reconciliation.repository.QuotaLeaseRepository;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;
import ru.ludwigandreas.reconciliation.repository.SyncRemoteJobRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code reconciliation} actuator endpoint: what every task is doing, and the operations somebody
 * needs at three in the morning.
 *
 * <h2>Why the write operations take an {@code action} parameter</h2>
 *
 * <p>Actuator routes by selector segment, not by verb, so eleven distinct operations would be eleven
 * endpoints or a selector grammar nobody can guess. One write operation with a named action keeps the
 * surface small and the audit trail uniform - every mutating call goes through the same place, and
 * therefore is recorded the same way, including the ones added later.
 *
 * <h2>Destructive operations</h2>
 *
 * <p>Cancelling a remote job and force-releasing a quota lease can both make this system's view and
 * the partner's disagree. They are off unless
 * {@code ludwig.reconciliation.endpoint.allow-destructive-operations} is on, and they require
 * {@code confirm=true} in the call itself even then - two switches, because the environment where
 * they are permissible and the moment they are intended are different decisions.
 *
 * <p>Secure the endpoint through {@code security-spring-boot-starter} like any other; this class
 * records who did what and refuses what it is not allowed to do, and does not authenticate anybody.
 */
@Endpoint(id = "reconciliation")
public class ReconciliationEndpoint {

    /** How many quarantined records a detail view lists before it starts being a report rather than a view. */
    private static final int QUARANTINE_SAMPLE = 20;

    private static final Logger log = LoggerFactory.getLogger(ReconciliationEndpoint.class);

    private final TaskRegistry registry;
    private final ReconciliationRuntime runtime;
    private final TaskRunner runner;
    private final TaskStateService taskState;
    private final SyncInboxRecordRepository records;
    private final SyncRemoteJobRepository jobs;
    private final QuotaLeaseRepository leases;
    private final DatabaseQuota quota;
    private final ReconciliationProperties properties;
    private final AuditSink auditLogger;
    private final AuditorProvider<String> auditor;

    /**
     * Creates the endpoint.
     *
     * @param registry    the discovered tasks
     * @param runtime     the scheduled passes
     * @param runner      the fetch run, for dry runs and backfills
     * @param taskState   cursor and watermark
     * @param records     the staging table
     * @param jobs        the job table
     * @param leases      the lease table
     * @param quota       the quota
     * @param properties  the bound configuration
     * @param auditLogger the audit trail
     * @param auditor     who is calling
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReconciliationEndpoint(TaskRegistry registry,
                                  ReconciliationRuntime runtime,
                                  TaskRunner runner,
                                  TaskStateService taskState,
                                  SyncInboxRecordRepository records,
                                  SyncRemoteJobRepository jobs,
                                  QuotaLeaseRepository leases,
                                  DatabaseQuota quota,
                                  ReconciliationProperties properties,
                                  AuditSink auditLogger,
                                  AuditorProvider<String> auditor) {
        this.registry = registry;
        this.runtime = runtime;
        this.runner = runner;
        this.taskState = taskState;
        this.records = records;
        this.jobs = jobs;
        this.leases = leases;
        this.quota = quota;
        this.properties = properties;
        this.auditLogger = auditLogger;
        this.auditor = auditor;
    }

    /**
     * Every task, with its schedule, backlog and freshness, plus every quota's saturation.
     *
     * @return the overview
     */
    @ReadOperation
    public Map<String, Object> overview() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("tasks", registry.all().stream().map(this::summarize).toList());
        view.put("quotas", quota.names().stream().map(this::summarizeQuota).toList());
        view.put("passes", runtime.passNames());
        return view;
    }

    /**
     * One task in detail: its settings, its state, its in-flight jobs and a sample of what is stuck.
     *
     * @param task the task name
     * @return the detail view
     */
    @ReadOperation
    public Map<String, Object> task(@Selector String task) {
        RegisteredTask<?, ?, ?> registered = require(task);
        TaskSettings settings = registered.settings();

        Map<String, Object> view = new LinkedHashMap<>(summarize(registered));
        view.put("mode", settings.mode());
        view.put("notFound", settings.notFound());
        view.put("retry", Map.of(
                "maxAttempts", settings.retry().maxAttempts(),
                "initialInterval", settings.retry().backoff().initialInterval().toString(),
                "maxInterval", settings.retry().backoff().maxInterval().toString(),
                "jitter", settings.retry().backoff().jitter()));
        view.put("state", taskState.find(task, DemandTier.HOT).map(state -> Map.of(
                "cursor", String.valueOf(state.getCursor()),
                "watermark", String.valueOf(state.getWatermark()),
                "lastRunAt", String.valueOf(state.getLastRunAt()),
                "lastRunId", String.valueOf(state.getLastRunId()))).orElse(Map.of()));
        view.put("inFlightJobs", jobs.findByTaskNameAndStateIn(task, RemoteJobState.uncovered())
                .stream().map(ReconciliationEndpoint::describeJob).toList());
        view.put("quarantinedSample", records
                .findByTaskNameAndStatusOrderByReceivedAtDesc(task, SyncRecordStatus.QUARANTINED,
                        PageRequest.of(0, QUARANTINE_SAMPLE))
                .stream().map(ReconciliationEndpoint::describeRecord).toList());
        return view;
    }

    /**
     * Performs an operation.
     *
     * @param task    the task to act on
     * @param action  one of {@code run}, {@code dry-run}, {@code backfill}, {@code reset-cursor},
     *                {@code requeue-quarantined}, {@code requeue-job}, {@code cancel-job},
     *                {@code release-lease}
     * @param tier    {@code hot} or {@code cold}, for {@code run}, {@code dry-run} and
     *                {@code reset-cursor}
     * @param keys    comma-separated correlation keys, for {@code backfill}
     * @param jobId   the remote job, for {@code requeue-job} and {@code cancel-job}
     * @param leaseId the quota lease, for {@code release-lease}
     * @param confirm required for a destructive action; see the class comment
     * @return what happened
     */
    @WriteOperation
    @SuppressWarnings("checkstyle:ParameterNumber")
    public Map<String, Object> operate(@Selector String task,
                                       String action,
                                       String tier,
                                       String keys,
                                       String jobId,
                                       String leaseId,
                                       Boolean confirm) {
        require(task);
        String operation = action == null ? "" : action.toLowerCase(Locale.ROOT);
        Map<String, Object> result = switch (operation) {
            case "run" -> run(task, tier);
            case "dry-run" -> runner.dryRun(require(task), tierOf(tier));
            case "backfill" -> backfill(task, keys);
            case "reset-cursor" -> resetCursor(task, tier);
            case "requeue-quarantined" -> requeueQuarantined(task);
            case "requeue-job" -> requeueJob(task, jobId);
            case "cancel-job" -> cancelJob(task, jobId, confirm);
            case "release-lease" -> releaseLease(task, leaseId, confirm);
            default -> throw new IllegalArgumentException("Unknown action '" + action + "'. Actions: "
                    + "run, dry-run, backfill, reset-cursor, requeue-quarantined, requeue-job, "
                    + "cancel-job, release-lease");
        };
        audit(task, operation, String.valueOf(result));
        return result;
    }

    private Map<String, Object> run(String task, String tier) {
        String pass = task + ":" + (tier == null ? "hot" : tier.toLowerCase(Locale.ROOT));
        // A task using the async-job shape has no hot pass; its equivalent trigger is the submit pass.
        String resolved = runtime.passNames().contains(pass) ? pass : task + ":submit";
        boolean ran = runtime.runNow(resolved);
        return Map.of("pass", resolved, "ran", ran,
                "note", ran ? "completed" : "a run was already in progress; nothing was started");
    }

    /**
     * Fetches an explicit set of keys, bypassing the demand query.
     *
     * <p>Goes through the same walker, staging and apply path as a scheduled run, so a backfilled
     * record keeps the stale-write and idempotency guards - which is what stops a backfill from
     * overwriting state that is newer than what the partner is about to return.
     */
    private Map<String, Object> backfill(String task, String keys) {
        if (keys == null || keys.isBlank()) {
            throw new IllegalArgumentException("backfill requires 'keys': a comma-separated list of "
                    + "correlation keys, in the form this task's KeyCodec writes them");
        }
        List<String> requested = Arrays.stream(keys.split(","))
                .map(String::trim)
                .filter(key -> !key.isBlank())
                .toList();
        int staged = runner.backfill(require(task), requested);
        return Map.of("task", task, "requested", requested.size(), "staged", staged,
                "note", "the watermark was not advanced; a backfill visits chosen keys, not a range "
                        + "of time");
    }

    private Map<String, Object> resetCursor(String task, String tier) {
        DemandTier resolved = tierOf(tier);
        taskState.reset(task, resolved);
        return Map.of("task", task, "tier", resolved,
                "note", "cursor and watermark cleared; the next run starts from the beginning");
    }

    private Map<String, Object> requeueQuarantined(String task) {
        int requeued = records.requeueQuarantined(task, Instant.now());
        return Map.of("task", task, "requeued", requeued,
                "note", "attempt counters reset, so each record gets a whole budget against "
                        + "whatever was fixed");
    }

    private Map<String, Object> requeueJob(String task, String jobId) {
        UUID id = requireId(jobId, "jobId");
        int requeued = records.requeueForJob(id, Instant.now());
        return Map.of("task", task, "jobId", id, "requeued", requeued);
    }

    private Map<String, Object> cancelJob(String task, String jobId, Boolean confirm) {
        requireDestructive("cancel-job", confirm);
        UUID id = requireId(jobId, "jobId");
        SyncRemoteJob job = jobs.getByIdOrThrow(id);
        // Deliberately marks the row rather than calling the partner from an HTTP thread: the
        // maintenance pass owns cancellation, holds the right locks, and releases the lease in the
        // right order. This makes the job expire at the next sweep.
        job.setExpiresAt(Instant.now());
        job.setLastError("Cancelled by " + principal() + " through the reconciliation endpoint");
        jobs.save(job);
        log.warn("Operator {} marked job {} of task '{}' for cancellation", principal(), id, task);
        return Map.of("task", task, "jobId", id,
                "note", "marked expired; the maintenance pass will cancel it at the partner and "
                        + "release its quota slot");
    }

    private Map<String, Object> releaseLease(String task, String leaseId, Boolean confirm) {
        requireDestructive("release-lease", confirm);
        UUID id = requireId(leaseId, "leaseId");
        QuotaLease lease = leases.getByIdOrThrow(id);
        leases.deleteById(id);
        log.warn("Operator {} force-released lease {} of quota '{}'. The work it covered may still be "
                        + "running at the partner, so the number of concurrent jobs there can now "
                        + "exceed the configured limit.", principal(), id, lease.getQuotaName());
        return Map.of("task", task, "leaseId", id, "quota", lease.getQuotaName(),
                "warning", "the work this slot covered may still be running at the partner; the "
                        + "configured limit can now be exceeded");
    }

    private static DemandTier tierOf(String tier) {
        return tier == null ? DemandTier.HOT : DemandTier.valueOf(tier.toUpperCase(Locale.ROOT));
    }

    private Map<String, Object> summarize(RegisteredTask<?, ?, ?> task) {
        TaskSettings settings = task.settings();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", settings.name());
        view.put("enabled", settings.enabled());
        view.put("shape", settings.fetch().shape());
        view.put("schedule", settings.hotSchedule().isCron()
                ? settings.hotSchedule().cron() : settings.hotSchedule().fixedDelay().toString());
        view.put("coldSchedule", settings.coldScheduleOrEmpty()
                .map(cold -> cold.isCron() ? cold.cron() : cold.fixedDelay().toString()).orElse(null));
        view.put("freshnessTarget", settings.demand().freshnessTarget().toString());
        // The figure the SLO alert fires on, shown next to the target it is measured against.
        view.put("freshnessLagSeconds", runtime.freshnessLagSeconds(settings.name()).orElse(0.0));
        view.put("backlog", records.countByTaskNameAndStatusIn(settings.name(),
                Set.of(SyncRecordStatus.STAGED, SyncRecordStatus.FAILED, SyncRecordStatus.DEFERRED)));
        view.put("quarantined", records.countByTaskNameAndStatusIn(settings.name(),
                Set.of(SyncRecordStatus.QUARANTINED)));
        view.put("lastRunAt", taskState.find(settings.name(), DemandTier.HOT)
                .map(state -> String.valueOf(state.getLastRunAt())).orElse(null));
        view.put("quota", settings.quota());
        view.put("rateLimit", settings.rateLimit());
        return view;
    }

    private Map<String, Object> summarizeQuota(String name) {
        ReconciliationProperties.Quota settings = properties.getQuotas().get(name);
        List<QuotaLease> live = leases.findByQuotaNameAndExpiresAtGreaterThan(name, Instant.now());
        List<Map<String, Object>> holders = new ArrayList<>();
        live.forEach(lease -> holders.add(Map.of(
                "leaseId", lease.getId(),
                "task", lease.getTaskName(),
                "owner", lease.getOwnerInstance(),
                "jobId", String.valueOf(lease.getJobId()),
                "expiresAt", lease.getExpiresAt(),
                "maxLifetimeAt", lease.getMaxLifetimeAt())));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", name);
        view.put("limit", settings == null ? 0 : settings.getMaxConcurrent());
        view.put("inFlight", live.size());
        view.put("oldestWaiterAgeSeconds", quota.oldestWaiterAgeSeconds(name));
        view.put("reclaimPolicy", settings == null ? null : settings.getReclaim());
        view.put("holders", holders);
        return view;
    }

    private static Map<String, Object> describeJob(SyncRemoteJob job) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", job.getId());
        view.put("state", job.getState());
        view.put("handle", job.getExternalHandle());
        view.put("createdAt", job.getCreatedAt());
        view.put("expiresAt", job.getExpiresAt());
        view.put("pollAttempts", job.getPollAttempts());
        view.put("ownerInstance", job.getOwnerInstance());
        view.put("quotaLeaseId", job.getQuotaLeaseId());
        view.put("lastError", job.getLastError());
        return view;
    }

    private static Map<String, Object> describeRecord(SyncInboxRecord record) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", record.getId());
        view.put("kind", record.getKind());
        view.put("correlationKey", record.getCorrelationKey());
        view.put("attempts", record.getAttempts());
        view.put("receivedAt", record.getReceivedAt());
        view.put("lastError", record.getLastError());
        return view;
    }

    private RegisteredTask<?, ?, ?> require(String task) {
        return registry.find(task).orElseThrow(() -> new IllegalArgumentException(
                "No such task: '" + task + "'. Tasks: " + String.join(", ", registry.names())));
    }

    private void requireDestructive(String action, Boolean confirm) {
        if (!properties.getEndpoint().isAllowDestructiveOperations()) {
            throw new IllegalStateException("'" + action + "' is destructive and is switched off here. "
                    + "Set ludwig.reconciliation.endpoint.allow-destructive-operations=true to enable it.");
        }
        if (!Boolean.TRUE.equals(confirm)) {
            throw new IllegalArgumentException("'" + action + "' can make this system's view and the "
                    + "partner's disagree. Repeat the call with confirm=true.");
        }
    }

    private static UUID requireId(String value, String parameter) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("This action requires a " + parameter);
        }
        return UUID.fromString(value);
    }

    private void audit(String task, String action, String detail) {
        auditLogger.record(ReconciliationAuditEvent.builder(task, Category.OPERATOR, "operator." + action)
                .subject(task)
                .principal(principal())
                .detail(detail)
                .build());
    }

    private String principal() {
        return auditor.getCurrentAuditor().orElse("anonymous");
    }
}
