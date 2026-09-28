package ru.ludwigandreas.reconciliation.config;

import ru.ludwigandreas.job.core.backoff.BackoffPolicy;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Merges {@code defaults} into each task and fills whatever is still unset from this module's
 * built-in values, producing the non-null {@link TaskSettings} the engine uses.
 *
 * <h2>Why this is written out rather than left to the binder</h2>
 *
 * <p>Because Spring does not do it. {@code ludwig.reconciliation.defaults} and
 * {@code ludwig.reconciliation.tasks.billing-status} are two unrelated objects as far as relaxed
 * binding is concerned, and a service that assumed a {@code defaults} block would be inherited would
 * find it silently ignored - every task running on the module's built-in retry budget while the
 * configuration file says otherwise. Writing the merge down also means there is exactly one place to
 * read to find out where a value came from.
 *
 * <h2>The precedence, in one sentence</h2>
 *
 * <p>Task, then {@code defaults}, then the built-in value below - per field, not per block, so a task
 * that sets only {@code retry.max-attempts} still inherits the default interval and multiplier rather
 * than dropping the whole {@code retry} block on the floor.
 */
public final class TaskSettingsResolver {

    // Built-in values, used when neither the task nor the defaults block says anything. Each is a
    // deliberate choice rather than a round number; see the README's configuration table.

    /** Chunk size for a batched fetcher: large enough to be worth a round trip, small enough to retry. */
    static final int DEFAULT_BATCH_SIZE = 100;

    /** Page size for a paged sweep, and for collecting a job's result. */
    static final int DEFAULT_PAGE_SIZE = 500;

    /** Calls in flight per task. Deliberately small: a quota, not this, is where a partner's limit belongs. */
    static final int DEFAULT_MAX_CONCURRENCY = 4;

    /** Keys per submitted asynchronous job. */
    static final int DEFAULT_SUBMIT_BATCH_SIZE = 500;

    /** Records per run. Enough to catch up over a few runs; not enough to load a backlog into memory. */
    static final int DEFAULT_MAX_RECORDS_PER_RUN = 5_000;

    /** Records claimed per apply pass. */
    static final int DEFAULT_APPLY_BATCH_SIZE = 200;

    /** Attempts before a record is quarantined. */
    static final int DEFAULT_MAX_ATTEMPTS = 8;

    /** How many new asynchronous jobs one submit pass may start. */
    static final int DEFAULT_MAX_CONCURRENT_SUBMITS = 2;

    /** How many asynchronous job results may be collected at once. */
    static final int DEFAULT_COLLECT_CONCURRENCY = 1;

    private static final ProcessingMode DEFAULT_MODE = ProcessingMode.STAGED;
    private static final NotFoundPolicy DEFAULT_NOT_FOUND = NotFoundPolicy.IGNORE;
    private static final Duration DEFAULT_FIXED_DELAY = Duration.ofMinutes(1);
    private static final Duration DEFAULT_RUN_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration DEFAULT_FRESHNESS_TARGET = Duration.ofMinutes(15);
    private static final Duration DEFAULT_APPLY_FIXED_DELAY = Duration.ofSeconds(10);
    private static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DEFAULT_INITIAL_INTERVAL = Duration.ofSeconds(2);
    private static final double DEFAULT_MULTIPLIER = 2.0;
    private static final Duration DEFAULT_MAX_INTERVAL = Duration.ofMinutes(10);
    private static final double DEFAULT_JITTER = 0.3;
    private static final Duration DEFAULT_JOB_SUBMIT_DELAY = Duration.ofMinutes(1);
    private static final Duration DEFAULT_JOB_POLL_DELAY = Duration.ofSeconds(30);
    private static final double DEFAULT_JOB_POLL_MULTIPLIER = 1.5;
    private static final Duration DEFAULT_JOB_POLL_MAX_INTERVAL = Duration.ofMinutes(5);
    private static final Duration DEFAULT_JOB_COLLECT_DELAY = Duration.ofMinutes(1);
    private static final Duration DEFAULT_JOB_MAX_LIFETIME = Duration.ofHours(6);
    private static final Duration DEFAULT_SUBMIT_GRACE_PERIOD = Duration.ofMinutes(2);

    private TaskSettingsResolver() {
    }

    /**
     * Resolves every configured task.
     *
     * @param properties the bound configuration
     * @return settings per task name, in configuration order
     */
    public static Map<String, TaskSettings> resolveAll(ReconciliationProperties properties) {
        Map<String, TaskSettings> resolved = new LinkedHashMap<>();
        properties.getTasks().forEach((name, task) -> resolved.put(name, resolve(name, task, properties)));
        return resolved;
    }

    /**
     * Resolves one task.
     *
     * @param name       the task's configuration key
     * @param task       its configuration block
     * @param properties the whole bound configuration, for the defaults block
     * @return the merged, non-null settings
     */
    public static TaskSettings resolve(String name,
                                       ReconciliationProperties.Task task,
                                       ReconciliationProperties properties) {
        ReconciliationProperties.Defaults defaults = properties.getDefaults();
        return new TaskSettings(
                name,
                task.isEnabled(),
                first(task.getMode(), defaults.getMode(), DEFAULT_MODE),
                first(task.getNotFound(), defaults.getNotFound(), DEFAULT_NOT_FOUND),
                resolveFetch(task.getFetch()),
                resolveSchedule(task.getSchedule(), defaults.getSchedule()),
                task.getColdSchedule() == null
                        ? null : resolveSchedule(task.getColdSchedule(), defaults.getSchedule()),
                firstDuration(task.getSchedule(), defaults.getSchedule(),
                        ReconciliationProperties.Schedule::getRunTimeout, DEFAULT_RUN_TIMEOUT),
                resolveDemand(task.getDemand(), defaults.getDemand()),
                resolveApply(task.getApply(), defaults.getApply()),
                resolveRetry(task.getRetry(), defaults.getRetry()),
                resolveAudit(task.getAudit(), defaults.getAudit()),
                task.getRestClient(),
                task.getQuota(),
                task.getRateLimit(),
                resolveJob(task));
    }

    private static TaskSettings.FetchSettings resolveFetch(ReconciliationProperties.Fetch fetch) {
        return new TaskSettings.FetchSettings(
                fetch.getShape(),
                orElse(fetch.getBatchSize(), DEFAULT_BATCH_SIZE),
                orElse(fetch.getPageSize(), DEFAULT_PAGE_SIZE),
                orElse(fetch.getMaxConcurrency(), DEFAULT_MAX_CONCURRENCY),
                orElse(fetch.getCheckpoint(), Boolean.TRUE),
                orElse(fetch.getSubmitBatchSize(), DEFAULT_SUBMIT_BATCH_SIZE));
    }

    /**
     * Builds a schedule, preferring the task's trigger over the defaults block's.
     *
     * <p>A cron expression and a fixed delay are mutually exclusive, so they are inherited as a pair:
     * a task that sets {@code cron} must not also pick up a default {@code fixed-delay}, which would
     * make the pair contradictory and fail {@link ScheduleSpec}'s own validation with a message about
     * a combination the configuration file never actually contains.
     */
    private static ScheduleSpec resolveSchedule(ReconciliationProperties.Schedule task,
                                                ReconciliationProperties.Schedule defaults) {
        String cron = first(task == null ? null : task.getCron(), defaults.getCron(), null);
        Duration fixedDelay = first(task == null ? null : task.getFixedDelay(), defaults.getFixedDelay(), null);
        if (task != null && task.getCron() != null && !task.getCron().isBlank()) {
            return ScheduleSpec.cron(task.getCron());
        }
        if (task != null && task.getFixedDelay() != null) {
            return ScheduleSpec.fixedDelay(task.getFixedDelay(), initialDelay(task, defaults, task.getFixedDelay()));
        }
        if (cron != null && !cron.isBlank()) {
            return ScheduleSpec.cron(cron);
        }
        Duration delay = fixedDelay == null ? DEFAULT_FIXED_DELAY : fixedDelay;
        return ScheduleSpec.fixedDelay(delay, initialDelay(task, defaults, delay));
    }

    private static Duration initialDelay(ReconciliationProperties.Schedule task,
                                         ReconciliationProperties.Schedule defaults,
                                         Duration fallback) {
        return first(task == null ? null : task.getInitialDelay(), defaults.getInitialDelay(), fallback);
    }

    private static TaskSettings.DemandSettings resolveDemand(ReconciliationProperties.Demand task,
                                                             ReconciliationProperties.Demand defaults) {
        return new TaskSettings.DemandSettings(
                merge(task, defaults, ReconciliationProperties.Demand::getMaxRecordsPerRun,
                        () -> DEFAULT_MAX_RECORDS_PER_RUN),
                merge(task, defaults, ReconciliationProperties.Demand::getFreshnessTarget,
                        () -> DEFAULT_FRESHNESS_TARGET),
                merge(task, defaults, ReconciliationProperties.Demand::getIncremental, () -> Boolean.FALSE));
    }

    private static TaskSettings.ApplySettings resolveApply(ReconciliationProperties.Apply task,
                                                           ReconciliationProperties.Apply defaults) {
        Duration fixedDelay = merge(task, defaults, ReconciliationProperties.Apply::getFixedDelay,
                () -> DEFAULT_APPLY_FIXED_DELAY);
        return new TaskSettings.ApplySettings(
                ScheduleSpec.fixedDelay(fixedDelay),
                merge(task, defaults, ReconciliationProperties.Apply::getBatchSize, () -> DEFAULT_APPLY_BATCH_SIZE),
                merge(task, defaults, ReconciliationProperties.Apply::getDrainTimeout, () -> DEFAULT_DRAIN_TIMEOUT));
    }

    private static TaskSettings.RetrySettings resolveRetry(ReconciliationProperties.Retry task,
                                                           ReconciliationProperties.Retry defaults) {
        return new TaskSettings.RetrySettings(
                merge(task, defaults, ReconciliationProperties.Retry::getMaxAttempts, () -> DEFAULT_MAX_ATTEMPTS),
                new BackoffPolicy(
                        merge(task, defaults, ReconciliationProperties.Retry::getInitialInterval,
                                () -> DEFAULT_INITIAL_INTERVAL),
                        merge(task, defaults, ReconciliationProperties.Retry::getMultiplier,
                                () -> DEFAULT_MULTIPLIER),
                        merge(task, defaults, ReconciliationProperties.Retry::getMaxInterval,
                                () -> DEFAULT_MAX_INTERVAL),
                        merge(task, defaults, ReconciliationProperties.Retry::getJitter, () -> DEFAULT_JITTER)));
    }

    private static TaskSettings.AuditSettings resolveAudit(ReconciliationProperties.Audit task,
                                                           ReconciliationProperties.Audit defaults) {
        return new TaskSettings.AuditSettings(
                merge(task, defaults, ReconciliationProperties.Audit::getEnabled, () -> Boolean.TRUE),
                merge(task, defaults, ReconciliationProperties.Audit::getPersist, () -> Boolean.FALSE));
    }

    /**
     * Resolves the asynchronous-job block, or returns null for a task that is not that shape.
     *
     * <p>{@code on-ambiguous-submit} is passed through exactly as configured, including when it is
     * absent. Substituting a default here would defeat the whole point of the setting: the validator
     * has to be able to see that nobody chose, and refuse to start.
     */
    private static TaskSettings.JobSettings resolveJob(ReconciliationProperties.Task task) {
        if (task.getFetch().getShape() != FetchShape.ASYNC_JOB || task.getJob() == null) {
            return null;
        }
        ReconciliationProperties.Job job = task.getJob();
        return new TaskSettings.JobSettings(
                ScheduleSpec.fixedDelay(orElse(job.getSubmit().getFixedDelay(), DEFAULT_JOB_SUBMIT_DELAY)),
                orElse(job.getSubmit().getMaxConcurrentSubmits(), DEFAULT_MAX_CONCURRENT_SUBMITS),
                ScheduleSpec.fixedDelay(orElse(job.getPoll().getFixedDelay(), DEFAULT_JOB_POLL_DELAY)),
                orElse(job.getPoll().getBackoffMultiplier(), DEFAULT_JOB_POLL_MULTIPLIER),
                orElse(job.getPoll().getMaxInterval(), DEFAULT_JOB_POLL_MAX_INTERVAL),
                orElse(job.getPoll().getHonourRetryAfter(), Boolean.TRUE),
                ScheduleSpec.fixedDelay(orElse(job.getCollect().getFixedDelay(), DEFAULT_JOB_COLLECT_DELAY)),
                orElse(job.getCollect().getMaxConcurrency(), DEFAULT_COLLECT_CONCURRENCY),
                orElse(job.getMaxLifetime(), DEFAULT_JOB_MAX_LIFETIME),
                job.getOnAmbiguousSubmit(),
                orElse(job.getSubmitGracePeriod(), DEFAULT_SUBMIT_GRACE_PERIOD));
    }

    /** Task value, else defaults value, else the built-in - the whole precedence rule, once. */
    private static <B, V> V merge(B task, B defaults, Function<B, V> field, Supplier<V> builtIn) {
        V fromTask = task == null ? null : field.apply(task);
        if (fromTask != null) {
            return fromTask;
        }
        V fromDefaults = defaults == null ? null : field.apply(defaults);
        return fromDefaults != null ? fromDefaults : builtIn.get();
    }

    private static Duration firstDuration(ReconciliationProperties.Schedule task,
                                          ReconciliationProperties.Schedule defaults,
                                          Function<ReconciliationProperties.Schedule, Duration> field,
                                          Duration builtIn) {
        return merge(task, defaults, field, () -> builtIn);
    }

    private static <V> V first(V preferred, V fallback, V builtIn) {
        if (preferred != null) {
            return preferred;
        }
        return fallback != null ? fallback : builtIn;
    }

    private static <V> V orElse(V value, V builtIn) {
        return value != null ? value : builtIn;
    }
}
