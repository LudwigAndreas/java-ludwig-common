package ru.ludwigandreas.reconciliation.admin.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.engine.ReconciliationRuntime;
import ru.ludwigandreas.reconciliation.engine.RegisteredTask;
import ru.ludwigandreas.reconciliation.engine.TaskRegistry;
import ru.ludwigandreas.reconciliation.engine.TaskRunner;
import ru.ludwigandreas.reconciliation.engine.TaskStateService;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationOperations;
import ru.ludwigandreas.reconciliation.entity.QuotaLease;
import ru.ludwigandreas.reconciliation.entity.RemoteJobState;
import ru.ludwigandreas.reconciliation.entity.SyncInboxRecord;
import ru.ludwigandreas.reconciliation.entity.SyncRecordStatus;
import ru.ludwigandreas.reconciliation.entity.SyncRemoteJob;
import ru.ludwigandreas.reconciliation.quota.DatabaseQuota;
import ru.ludwigandreas.reconciliation.repository.QuotaLeaseRepository;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;
import ru.ludwigandreas.reconciliation.repository.SyncRemoteJobRepository;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.admin.AdminOutcome;

/**
 * The {@code reconciliation} area's operations - every action the reconciliation actuator endpoint offered, on the
 * same engine calls.
 *
 * <p>Ported from that endpoint, and changed only where the administrative surface changes the rules: an unknown task
 * is a {@code 404} rather than an {@code IllegalArgumentException}; the destructive-operations switch and the
 * {@code confirm} flag are gone, because whether a deployment permits those actions is now whether any role is
 * granted {@code reconciliation.privileged}, and the mandatory reason is the moment's intent; and each action is
 * recorded once, through {@link AdminAudit}, instead of as a module-specific operator event.
 *
 * <p>Results are the engine's own descriptions, as the endpoint returned them: the shapes differ per action and per
 * task kind, and inventing a type per shape would be a second vocabulary for what the engine already says.
 */
public class ReconciliationAdminService {

    /** How many quarantined records a detail view lists before it is a report rather than a view. */
    private static final int QUARANTINE_SAMPLE = 20;

    private static final String TASK = "reconciliation-task";
    private static final String SCOPE = "reconciliation-scope";
    private static final String REMOTE_JOB = "reconciliation-remote-job";
    private static final String LEASE = "reconciliation-quota-lease";

    private final TaskRegistry registry;
    private final ReconciliationRuntime runtime;
    private final TaskRunner runner;
    private final TaskStateService taskState;
    private final SyncInboxRecordRepository records;
    private final SyncRemoteJobRepository jobs;
    private final QuotaLeaseRepository leases;
    private final DatabaseQuota quota;
    private final ReconciliationProperties properties;
    private final EnumerationOperations enumerations;
    private final AdminAudit audit;

    /**
     * Creates the service.
     *
     * @param engine       the task-kind collaborators
     * @param enumerations the enumeration operations, or null when no enumeration is configured
     * @param audit        the administrative audit
     */
    public ReconciliationAdminService(Engine engine, EnumerationOperations enumerations, AdminAudit audit) {
        this.registry = engine.registry();
        this.runtime = engine.runtime();
        this.runner = engine.runner();
        this.taskState = engine.taskState();
        this.records = engine.records();
        this.jobs = engine.jobs();
        this.leases = engine.leases();
        this.quota = engine.quota();
        this.properties = engine.properties();
        this.enumerations = enumerations;
        this.audit = audit;
    }

    /**
     * The engine collaborators the service reads and acts through.
     *
     * @param registry   the discovered tasks
     * @param runtime    the scheduled passes
     * @param runner     the fetch run, for dry runs and backfills
     * @param taskState  cursor and watermark
     * @param records    the staging table
     * @param jobs       the job table
     * @param leases     the lease table
     * @param quota      the quota
     * @param properties the bound configuration
     */
    public record Engine(TaskRegistry registry, ReconciliationRuntime runtime, TaskRunner runner,
                         TaskStateService taskState, SyncInboxRecordRepository records,
                         SyncRemoteJobRepository jobs, QuotaLeaseRepository leases, DatabaseQuota quota,
                         ReconciliationProperties properties) {
    }

    /**
     * Every task, with its schedule, backlog and freshness, plus every quota's saturation and the enumerations.
     *
     * @return the overview
     */
    public Map<String, Object> overview() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("tasks", registry.all().stream().map(this::summarize).toList());
        view.put("quotas", quota.names().stream().map(this::summarizeQuota).toList());
        List<String> passes = new ArrayList<>(runtime.passNames());
        if (enumerations != null) {
            view.put("enumerations", enumerations.summaries());
            passes.addAll(enumerations.passNames());
        }
        view.put("passes", passes);
        return view;
    }

    /**
     * One task or enumeration in detail.
     *
     * @param task the name
     * @return the detail view
     */
    public Map<String, Object> task(String task) {
        if (isEnumeration(task)) {
            return enumerations.detail(task);
        }
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
                .stream().map(ReconciliationAdminService::describeJob).toList());
        view.put("quarantinedSample", records
                .findByTaskNameAndStatusOrderByReceivedAtDesc(task, SyncRecordStatus.QUARANTINED,
                        PageRequest.of(0, QUARANTINE_SAMPLE))
                .stream().map(ReconciliationAdminService::describeRecord).toList());
        return view;
    }

    /**
     * One scope of an enumeration.
     *
     * @param task  the enumeration
     * @param scope the scope key
     * @return the scope view
     */
    public Map<String, Object> scope(String task, String scope) {
        return requireEnumerations(task).scope(task, scope);
    }

    /**
     * Runs a task's pass now, or performs a dry run of it.
     *
     * @param task   the task or enumeration
     * @param tier   {@code hot} or {@code cold}; null for hot
     * @param dryRun whether to fetch and compare without applying
     * @return what happened
     */
    public Map<String, Object> run(String task, String tier, boolean dryRun) {
        if (isEnumeration(task)) {
            return recorded(TASK, task, "run", enumerations.operate(task, "run"));
        }
        RegisteredTask<?, ?, ?> registered = require(task);
        if (dryRun) {
            return recorded(TASK, task, "dry-run", runner.dryRun(registered, tierOf(tier)));
        }
        String pass = task + ":" + tierOf(tier).name().toLowerCase(Locale.ROOT);
        // A task using the async-job shape has no hot pass; its equivalent trigger is the submit pass.
        String resolved = runtime.passNames().contains(pass) ? pass : task + ":submit";
        if (!runtime.runNow(resolved)) {
            audit.record(TASK, task, AdminOutcome.REFUSED_BY_STATE, Map.of("action", "run", "pass", resolved));
            throw ReconciliationAdminException.alreadyRunning(resolved);
        }
        return recorded(TASK, task, "run", Map.of("pass", resolved, "ran", true));
    }

    /**
     * Fetches an explicit set of keys through the same walker, staging and apply path as a scheduled run, so a
     * backfilled record keeps the stale-write and idempotency guards.
     *
     * @param task the task
     * @param keys correlation keys, in the form the task's key codec writes them
     * @return what was staged
     */
    public Map<String, Object> backfill(String task, List<String> keys) {
        List<String> requested = keys == null ? List.of()
                : keys.stream().map(String::trim).filter(key -> !key.isBlank()).toList();
        if (requested.isEmpty()) {
            throw ReconciliationAdminException.keysRequired();
        }
        int staged = runner.backfill(require(task), requested);
        return recorded(TASK, task, "backfill", Map.of("task", task, "requested", requested.size(), "staged", staged));
    }

    /**
     * Clears a task's cursor and watermark, so the next run starts from the beginning.
     *
     * @param task the task
     * @param tier {@code hot} or {@code cold}
     * @return what was reset
     */
    public Map<String, Object> resetCursor(String task, String tier) {
        require(task);
        DemandTier resolved = tierOf(tier);
        taskState.reset(task, resolved);
        return recorded(TASK, task, "reset-cursor", Map.of("task", task, "tier", resolved));
    }

    /**
     * Requeues every quarantined record of a task with its attempt counters reset.
     *
     * @param task the task
     * @return how many were requeued
     */
    public Map<String, Object> requeueQuarantined(String task) {
        require(task);
        int requeued = records.requeueQuarantined(task, Instant.now());
        return recorded(TASK, task, "requeue-quarantined", Map.of("task", task, "requeued", requeued));
    }

    /**
     * Requeues the records a remote job covered.
     *
     * @param task  the task
     * @param jobId the remote job
     * @return how many were requeued
     */
    public Map<String, Object> requeueJob(String task, UUID jobId) {
        requireJob(task, jobId);
        int requeued = records.requeueForJob(jobId, Instant.now());
        return recorded(REMOTE_JOB, jobId.toString(), "requeue-job",
                Map.of("task", task, "jobId", jobId, "requeued", requeued));
    }

    /**
     * Marks a remote job for cancellation. The maintenance pass owns cancelling it at the partner and releasing its
     * quota slot, in the right order; this call only makes it expire at the next sweep.
     *
     * @param task  the task
     * @param jobId the remote job
     * @return what was marked
     */
    public Map<String, Object> cancelJob(String task, UUID jobId) {
        SyncRemoteJob job = requireJob(task, jobId);
        job.setExpiresAt(Instant.now());
        job.setLastError("Cancelled through the administrative surface by "
                + AdminInvocation.require().principal().subject());
        jobs.save(job);
        return recorded(REMOTE_JOB, jobId.toString(), "cancel-job", Map.of("task", task, "jobId", jobId));
    }

    /**
     * Deletes a quota lease. The work it covered may still be running at the partner, so the concurrent jobs there
     * can now exceed the configured limit.
     *
     * @param leaseId the lease
     * @return what was released
     */
    public Map<String, Object> releaseLease(UUID leaseId) {
        QuotaLease lease = leases.findById(leaseId)
                .orElseThrow(() -> ReconciliationAdminException.leaseNotFound(leaseId));
        leases.deleteById(leaseId);
        return recorded(LEASE, leaseId.toString(), "release-lease",
                Map.of("leaseId", leaseId, "quota", lease.getQuotaName(), "task", lease.getTaskName()));
    }

    /**
     * Runs an enumeration's discovery pass now.
     *
     * @param task the enumeration
     * @return what happened
     */
    public Map<String, Object> discover(String task) {
        return recorded(TASK, task, "discover", requireEnumerations(task).operate(task, "discover"));
    }

    /**
     * Requeues an enumeration's quarantined members.
     *
     * @param task the enumeration
     * @return how many were requeued
     */
    public Map<String, Object> requeueMembers(String task) {
        return recorded(TASK, task, "requeue-members", requireEnumerations(task).operate(task, "requeue-members"));
    }

    /**
     * Acts on one scope of an enumeration.
     *
     * @param task   the enumeration
     * @param scope  the scope key
     * @param action {@code walk}, {@code preview}, {@code reset-scope}, {@code discard-sweep} or
     *               {@code confirm-sweep}
     * @param kind   {@code full} to force a full walk; otherwise the planner chooses
     * @return what happened
     */
    public Map<String, Object> scopeAction(String task, String scope, String action, String kind) {
        Map<String, Object> result = requireEnumerations(task).operateScope(task, scope, action, kind);
        // A preview writes nothing and changes nothing, so it is not an administrative change to record.
        return "preview".equals(action) ? result : recorded(SCOPE, task + "/" + scope, action, result);
    }

    private Map<String, Object> recorded(String resourceType, String resourceId, String action,
                                         Map<String, Object> result) {
        audit.record(resourceType, resourceId, AdminOutcome.SUCCEEDED, Map.of("action", action));
        return result;
    }

    private boolean isEnumeration(String task) {
        return enumerations != null && enumerations.knows(task);
    }

    private EnumerationOperations requireEnumerations(String task) {
        if (!isEnumeration(task)) {
            throw ReconciliationAdminException.enumerationNotFound(task);
        }
        return enumerations;
    }

    private RegisteredTask<?, ?, ?> require(String task) {
        return registry.find(task).orElseThrow(() -> ReconciliationAdminException.taskNotFound(task));
    }

    private SyncRemoteJob requireJob(String task, UUID jobId) {
        require(task);
        return jobs.findById(jobId).filter(job -> task.equals(job.getTaskName()))
                .orElseThrow(() -> ReconciliationAdminException.remoteJobNotFound(jobId, task));
    }

    private static DemandTier tierOf(String tier) {
        if (tier == null) {
            return DemandTier.HOT;
        }
        try {
            return DemandTier.valueOf(tier.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ReconciliationAdminException.invalidTier(tier);
        }
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

    /** A quarantined record's coordinates and reason; its payload is never read. */
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
}
