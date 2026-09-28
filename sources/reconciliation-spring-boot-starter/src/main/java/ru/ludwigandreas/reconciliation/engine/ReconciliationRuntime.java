package ru.ludwigandreas.reconciliation.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.ScheduledJob;
import ru.ludwigandreas.reconciliation.api.DemandTier;
import ru.ludwigandreas.reconciliation.config.FetchShape;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.entity.SyncRecordKind;
import ru.ludwigandreas.reconciliation.entity.SyncRecordStatus;
import ru.ludwigandreas.reconciliation.job.RemoteJobCollectService;
import ru.ludwigandreas.reconciliation.job.RemoteJobMaintenanceService;
import ru.ludwigandreas.reconciliation.job.RemoteJobPollService;
import ru.ludwigandreas.reconciliation.job.RemoteJobSubmitService;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.quota.DatabaseQuota;
import ru.ludwigandreas.reconciliation.quota.QuotaReclaimService;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds every scheduled pass the module runs, owns their lifecycle, and is the single entry point
 * for triggering one by hand.
 *
 * <h2>What gets scheduled</h2>
 *
 * <p>Per enabled task: a hot fetch pass, a cold fetch pass if the task is tiered, and an apply pass.
 * A task using the asynchronous-job shape gets three more - submit, poll and collect - plus a
 * maintenance pass for expiry and ambiguous submits. Module-wide: a stale-record reclaimer and a
 * quota reclaim sweep.
 *
 * <p>That is a lot of small jobs rather than one big loop, and deliberately: each has its own cadence
 * for a reason that would be lost by merging it with any of the others, and each is separately
 * disableable, separately observable, and separately triggerable from the actuator endpoint.
 *
 * <h2>Gauges are registered here</h2>
 *
 * <p>Against suppliers, not set from inside the passes. A gauge only written while a pass is running
 * reports a stale value between passes and - much worse - keeps reporting the last healthy value of a
 * task that has stopped running altogether. That is exactly the failure the freshness-lag gauge exists
 * to catch, so it has to be pulled.
 */
public class ReconciliationRuntime implements SmartLifecycle {

    /** Statuses a record is in while it is still somewhere in the pipeline. */
    private static final Set<SyncRecordStatus> UNSETTLED = Set.of(
            SyncRecordStatus.STAGED, SyncRecordStatus.PROCESSING,
            SyncRecordStatus.FAILED, SyncRecordStatus.DEFERRED);

    /** Milliseconds per second, for the gauges that report an age in seconds. */
    private static final double MILLIS_PER_SECOND = 1000.0;

    /**
     * Drain allowance for the stale-record reclaimer: it is a single statement, so anything longer
     * than a moment means the database is unavailable, and waiting for that on shutdown helps nobody.
     */
    private static final Duration RECLAIM_DRAIN_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Drain allowance for the quota reclaim sweep, which may make network calls to verify that remote
     * work has really stopped before taking a slot back.
     */
    private static final Duration QUOTA_DRAIN_TIMEOUT = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRuntime.class);

    private final TaskRegistry registry;
    private final TaskRunner runner;
    private final ApplyService applyService;
    private final TaskStateService taskState;
    private final SyncInboxRecordRepository records;
    private final RemoteJobSubmitService submitService;
    private final RemoteJobPollService pollService;
    private final RemoteJobCollectService collectService;
    private final RemoteJobMaintenanceService maintenanceService;
    private final QuotaReclaimService quotaReclaim;
    private final DatabaseQuota quota;
    private final ReconciliationMetrics metrics;
    private final ReconciliationProperties properties;
    private final TaskScheduler scheduler;

    private final Map<String, ScheduledJob> jobs = new LinkedHashMap<>();
    private volatile boolean running;

    /**
     * Creates the runtime.
     *
     * @param registry           the discovered tasks
     * @param runner             the fetch run
     * @param applyService       the apply pass
     * @param taskState          cursor and watermark, for the actuator's task listing
     * @param records            the staging table, for the backlog gauges
     * @param submitService      the asynchronous-job submit pass
     * @param pollService        the asynchronous-job poll pass
     * @param collectService     the asynchronous-job collect pass
     * @param maintenanceService expiry and ambiguous-submit resolution
     * @param quotaReclaim       the quota reclaim sweep
     * @param quota              the quota, for its gauges
     * @param metrics            instrumentation
     * @param properties         the bound configuration
     * @param scheduler          the scheduler every pass registers itself with
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReconciliationRuntime(TaskRegistry registry,
                                 TaskRunner runner,
                                 ApplyService applyService,
                                 TaskStateService taskState,
                                 SyncInboxRecordRepository records,
                                 RemoteJobSubmitService submitService,
                                 RemoteJobPollService pollService,
                                 RemoteJobCollectService collectService,
                                 RemoteJobMaintenanceService maintenanceService,
                                 QuotaReclaimService quotaReclaim,
                                 DatabaseQuota quota,
                                 ReconciliationMetrics metrics,
                                 ReconciliationProperties properties,
                                 TaskScheduler scheduler) {
        this.registry = registry;
        this.runner = runner;
        this.applyService = applyService;
        this.taskState = taskState;
        this.records = records;
        this.submitService = submitService;
        this.pollService = pollService;
        this.collectService = collectService;
        this.maintenanceService = maintenanceService;
        this.quotaReclaim = quotaReclaim;
        this.quota = quota;
        this.metrics = metrics;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    @Override
    public void start() {
        registry.all().forEach(this::schedule);
        scheduleReclaimers();
        registerGauges();
        jobs.values().forEach(ScheduledJob::start);
        running = true;
        log.info("Reconciliation started {} scheduled pass(es) across {} task(s)",
                jobs.size(), registry.all().size());
    }

    @Override
    public void stop() {
        // Reversed so that the passes that produce work stop before the passes that consume it,
        // which lets a drain actually finish rather than racing new arrivals.
        List<ScheduledJob> ordered = new ArrayList<>(jobs.values());
        java.util.Collections.reverse(ordered);
        ordered.forEach(ScheduledJob::stop);
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle.DEFAULT_PHASE;
    }

    /**
     * Runs one pass by hand, now.
     *
     * @param passName the pass, as it appears in {@link #passNames()}
     * @return whether it ran; false if a run was already in progress
     */
    public boolean runNow(String passName) {
        ScheduledJob job = jobs.get(passName);
        if (job == null) {
            throw new IllegalArgumentException("No such pass: '" + passName + "'. Passes: "
                    + String.join(", ", passNames()));
        }
        return job.runNow();
    }

    /** Every scheduled pass's name. */
    public List<String> passNames() {
        return List.copyOf(jobs.keySet());
    }

    private void schedule(RegisteredTask<?, ?, ?> task) {
        TaskSettings settings = task.settings();
        if (!settings.enabled()) {
            log.info("Task '{}' is disabled; nothing is scheduled for it", settings.name());
            return;
        }
        Duration drain = settings.apply().drainTimeout();

        if (settings.fetch().shape() == FetchShape.ASYNC_JOB) {
            TaskSettings.JobSettings job = settings.jobOrEmpty().orElseThrow();
            add(settings.name() + ":submit", job.submitSchedule(), drain,
                    () -> submitService.submitPass(task));
            add(settings.name() + ":poll", job.pollSchedule(), drain,
                    () -> pollService.pollPass(task));
            add(settings.name() + ":collect", job.collectSchedule(), drain,
                    () -> collectService.collectPass(task));
            add(settings.name() + ":job-maintenance",
                    ScheduleSpec.fixedDelay(properties.getReclaim().getFixedDelay()), drain,
                    () -> maintenanceService.maintain(task));
        } else {
            add(settings.name() + ":hot", settings.hotSchedule(), drain,
                    () -> runner.run(task, DemandTier.HOT));
            settings.coldScheduleOrEmpty().ifPresent(cold ->
                    add(settings.name() + ":cold", cold, drain, () -> runner.run(task, DemandTier.COLD)));
        }

        add(settings.name() + ":apply", settings.apply().schedule(), drain,
                () -> applyService.runOnce(task));
    }

    private void scheduleReclaimers() {
        Duration delay = properties.getReclaim().getFixedDelay();
        add("reclaim:stale-records", ScheduleSpec.fixedDelay(delay), RECLAIM_DRAIN_TIMEOUT, () -> {
            int reclaimed = records.reclaimStale(
                    Instant.now().minus(properties.getReclaim().getStaleRecordTimeout()));
            if (reclaimed > 0) {
                log.warn("Reclaimed {} staged record(s) left PROCESSING by an instance that died",
                        reclaimed);
            }
            return reclaimed;
        });
        if (!properties.getQuotas().isEmpty()) {
            add("reclaim:quota-leases", ScheduleSpec.fixedDelay(delay), QUOTA_DRAIN_TIMEOUT,
                    quotaReclaim::sweep);
        }
    }

    private void add(String name, ScheduleSpec schedule, Duration drainTimeout,
                     java.util.function.Supplier<Integer> work) {
        jobs.put(name, new ScheduledJob(name, scheduler, schedule, drainTimeout, work::get));
    }

    private void registerGauges() {
        properties.getQuotas().keySet().forEach(name -> metrics.registerQuota(name,
                () -> quota.inFlight(name),
                () -> quota.limit(name),
                () -> quota.oldestWaiterAgeSeconds(name)));

        registry.all().forEach(task -> {
            String name = task.settings().name();
            metrics.registerStagedBacklog(name,
                    () -> records.countByTaskNameAndKindAndStatusIn(name, SyncRecordKind.RECORD, UNSETTLED),
                    () -> ageSeconds(records.findOldestReceivedAt(name, UNSETTLED)));
            metrics.registerQuarantined(name,
                    () -> records.countByTaskNameAndStatusIn(name, Set.of(SyncRecordStatus.QUARANTINED)));
            metrics.registerFreshnessLag(name, () -> freshnessLagSeconds(task.settings()));
        });
    }

    /**
     * The SLO metric: how far behind this task's data is, in seconds.
     *
     * <p>Defined from the two things that can make data stale, whichever is worse:
     *
     * <ul>
     *   <li><b>The oldest record still in the pipeline.</b> Something was fetched and has not been
     *       applied yet - it is staged, deferred, retrying, or claimed by an instance that is
     *       working on it.</li>
     *   <li><b>Time since the last completed run.</b> Nothing is in the pipeline because nothing is
     *       being fetched. This is the case every other metric misses: a task whose demand query
     *       stopped matching anything, or whose schedule stopped firing, fetches nothing, succeeds
     *       instantly, and reports a perfect run rate forever.</li>
     * </ul>
     *
     * <p>The task's {@code demand.freshness-target} is subtracted, so the gauge reads zero while the
     * task is within its own declared budget and climbs only once it is genuinely behind. That is what
     * makes one alert threshold work across tasks whose acceptable staleness differs by hours.
     */
    private double freshnessLagSeconds(TaskSettings settings) {
        Instant oldestStaged = records.findOldestReceivedAt(settings.name(), UNSETTLED);
        Instant lastRun = taskState.find(settings.name(), DemandTier.HOT)
                .map(state -> state.getLastRunAt())
                .orElse(null);

        double pipelineLag = ageSeconds(oldestStaged);
        // A task that has never completed a run is not "perfectly fresh"; it has never been fresh.
        double runLag = lastRun == null ? 0.0 : ageSeconds(lastRun);
        double worst = Math.max(pipelineLag, runLag);
        double target = settings.demand().freshnessTarget().toMillis() / MILLIS_PER_SECOND;
        return Math.max(0.0, worst - target);
    }

    /**
     * How far behind a task's data is, in seconds - the same number the SLO gauge reports.
     *
     * <p>Exposed so the actuator's listing shows the figure an operator is about to be alerted on,
     * rather than making them correlate a dashboard with a task list by hand.
     *
     * @param taskName the task
     * @return the lag, or empty if no such task is registered
     */
    public Optional<Double> freshnessLagSeconds(String taskName) {
        return registry.find(taskName).map(task -> freshnessLagSeconds(task.settings()));
    }

    private static double ageSeconds(Instant instant) {
        return instant == null ? 0.0 : Math.max(0.0,
                Duration.between(instant, Instant.now()).toMillis() / MILLIS_PER_SECOND);
    }

}
