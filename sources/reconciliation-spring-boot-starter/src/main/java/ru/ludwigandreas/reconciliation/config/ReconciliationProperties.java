package ru.ludwigandreas.reconciliation.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything this module can be told, under {@code ludwig.reconciliation}.
 *
 * <h2>Nullable fields, and why</h2>
 *
 * <p>Almost every task-level setting is a boxed type with no initializer. That is deliberate and it
 * is what makes {@code defaults} work: a {@code null} means "not said here", which is the only way to
 * distinguish a task that asked for {@code max-attempts: 8} from one that said nothing and should
 * inherit it. Fields with primitive types or eager defaults cannot express that difference, and a
 * merge built on them silently overrides the defaults block with zeros.
 *
 * <p>The merge itself is written out in {@link TaskSettingsResolver} rather than left to relaxed
 * binding, because relaxed binding does not do it at all - Spring binds {@code defaults} and
 * {@code tasks.billing-status} as two unrelated objects, and a service that assumed otherwise would
 * find its defaults quietly ignored.
 *
 * @see TaskSettingsResolver for the resolved, non-null view the engine actually uses
 * @see ReconciliationConfigurationValidator for the cross-field checks that run at startup
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ludwig.reconciliation")
public class ReconciliationProperties {

    /** Master switch for the whole module's autoconfiguration. */
    private boolean enabled = true;

    /**
     * Identity written into {@code locked_by} and {@code owner_instance}. Defaults to the shared
     * {@code job-core} instance identity, which is what keeps one pod looking like one instance
     * across every module that claims rows.
     */
    private String instance;

    /** Settings every task inherits unless it says otherwise. */
    @Valid
    private final Defaults defaults = new Defaults();

    /** Partner-scoped concurrency budgets, referenced by tasks by name. */
    @Valid
    private Map<String, Quota> quotas = new LinkedHashMap<>();

    /** Partner-scoped request-rate budgets, referenced by tasks by name. */
    @Valid
    private Map<String, RateLimit> rateLimits = new LinkedHashMap<>();

    /** The integrations. Each key must match a {@code @ReconciliationTask} bean's value. */
    @Valid
    private Map<String, Task> tasks = new LinkedHashMap<>();

    /** Whether this module applies its own changelog. */
    @Valid
    private final Liquibase liquibase = new Liquibase();

    /** Whether Micrometer instrumentation is registered. */
    @Valid
    private final Metrics metrics = new Metrics();

    /** The actuator endpoint's own settings. */
    @Valid
    private final Endpoint endpoint = new Endpoint();

    /** Recovery of rows and jobs left behind by an instance that died. */
    @Valid
    private final Reclaim reclaim = new Reclaim();

    /** Settings inherited by every task that does not override them. */
    @Getter
    @Setter
    public static class Defaults {

        /** Default processing mode. */
        private ProcessingMode mode = ProcessingMode.STAGED;

        /** Default policy for a key the partner does not know. */
        private NotFoundPolicy notFound = NotFoundPolicy.IGNORE;

        /** Default retry budget and backoff curve. */
        @Valid
        private final Retry retry = new Retry();

        /** Default audit settings. */
        @Valid
        private final Audit audit = new Audit();

        /** Default demand settings. */
        @Valid
        private final Demand demand = new Demand();

        /** Default apply-pass settings. */
        @Valid
        private final Apply apply = new Apply();

        /** Default run-level schedule settings that are not shape-specific. */
        @Valid
        private final Schedule schedule = new Schedule();
    }

    /** One integration. */
    @Getter
    @Setter
    public static class Task {

        /**
         * Whether this task runs at all. A disabled task is still validated - a configuration that is
         * wrong while switched off is a configuration that is wrong the moment somebody switches it
         * on, usually during an incident.
         */
        private boolean enabled = true;

        /** Overrides {@code defaults.mode}. */
        private ProcessingMode mode;

        /** Overrides {@code defaults.not-found}. */
        private NotFoundPolicy notFound;

        /** How external state is fetched. Required: there is no sensible default shape. */
        @Valid
        @NotNull
        private Fetch fetch = new Fetch();

        /** When the hot pass runs. */
        @Valid
        private Schedule schedule = new Schedule();

        /**
         * When the cold full sweep runs. Absent means this task has no tiering: one query, one
         * cadence, and {@code demand()} is always asked for the hot tier.
         */
        @Valid
        private Schedule coldSchedule;

        /** How much demand one run may take on. */
        @Valid
        private Demand demand;

        /** When staged records are applied. */
        @Valid
        private Apply apply;

        /** Overrides {@code defaults.retry}. */
        @Valid
        private Retry retry;

        /** Overrides {@code defaults.audit}. */
        @Valid
        private Audit audit;

        /** Name of the named REST client this task's fetcher uses; validated to exist at startup. */
        private String restClient;

        /** Name of the quota this task's calls are gated by; validated to exist at startup. */
        private String quota;

        /** Name of the rate limit this task's calls are gated by; validated to exist at startup. */
        private String rateLimit;

        /** Required when, and only when, {@code fetch.shape} is {@code async-job}. */
        @Valid
        private Job job;
    }

    /** How external state is fetched. */
    @Getter
    @Setter
    public static class Fetch {

        /** Which of the four shapes; must match the fetcher bean's interface. */
        @NotNull
        private FetchShape shape;

        /** Keys per call for {@code batched}. */
        @Positive
        private Integer batchSize;

        /** Records per page for {@code paged}, and for an async job's result collection. */
        @Positive
        private Integer pageSize;

        /**
         * How many calls this task may have in flight at once.
         *
         * <p>Per task, and therefore <em>not</em> a substitute for a quota: two tasks each allowed
         * four concurrent calls against one partner make eight. Use a quota to express what the
         * partner will tolerate, and this to express what this task should take of it.
         */
        @Positive
        private Integer maxConcurrency;

        /**
         * Whether a paged sweep persists its cursor and resumes after an interruption.
         *
         * <p>Turning it off is only right for a sweep short enough that restarting it costs less than
         * the risk of resuming from a cursor the partner has invalidated.
         */
        private Boolean checkpoint;

        /** Keys per submitted job for {@code async-job}. */
        @Positive
        private Integer submitBatchSize;
    }

    /** When something runs. */
    @Getter
    @Setter
    public static class Schedule {

        /** Delay between the end of one run and the start of the next. Mutually exclusive with {@code cron}. */
        private Duration fixedDelay;

        /** Spring cron expression, seconds first. Mutually exclusive with {@code fixed-delay}. */
        private String cron;

        /** How long after startup the first run happens. */
        private Duration initialDelay;

        /**
         * How long one run may take before the run lock is presumed abandoned.
         *
         * <p>Must exceed the worst-case run, which for a retrying fetcher means the whole retry
         * budget - otherwise the stale-run reclaimer hands the task to a second instance while the
         * first is still working, and both talk to the partner at once. The validator checks it.
         */
        private Duration runTimeout;
    }

    /** How much demand one run may take on, and how fresh this task's data is meant to be. */
    @Getter
    @Setter
    public static class Demand {

        /**
         * Hard cap on records per run.
         *
         * <p>What stops a task that has fallen a week behind from loading a million rows into memory
         * on its first tick back and taking the pod with it. A task that is behind should catch up
         * over several runs, visibly, rather than in one run that never completes.
         */
        @Positive
        private Integer maxRecordsPerRun;

        /**
         * How stale this task's data is allowed to get.
         *
         * <p>Not enforced - it is the denominator of the {@code reconciliation.freshness.lag} gauge,
         * which is the metric worth attaching an SLO to. Declaring it is how the task says what
         * "working" means for it, so that the gauge answers a question rather than reporting a number.
         */
        private Duration freshnessTarget;

        /**
         * Whether this task receives a persisted watermark as an {@code updated-since} parameter.
         *
         * <p>Only meaningful when the partner supports a delta parameter. When it does, this is
         * usually the difference between a sweep that can run often and one that cannot.
         */
        private Boolean incremental;
    }

    /** When staged records are applied, and how many at a time. */
    @Getter
    @Setter
    public static class Apply {

        /**
         * Delay between apply passes.
         *
         * <p>Separate from the fetch schedule on purpose: that independence is one of the main things
         * staging buys. A task can poll a partner every five minutes and still drain a backlog of
         * deferred records every ten seconds.
         */
        private Duration fixedDelay;

        /** Records claimed per apply pass. */
        @Positive
        private Integer batchSize;

        /** How long shutdown waits for an apply pass that is already running. */
        private Duration drainTimeout;
    }

    /** Retry budget and backoff curve. */
    @Getter
    @Setter
    public static class Retry {

        /** Attempts before a record is quarantined. */
        @Min(1)
        private Integer maxAttempts;

        /** First backoff interval. */
        private Duration initialInterval;

        /** Backoff growth factor. */
        @Min(1)
        private Double multiplier;

        /** Backoff ceiling. */
        private Duration maxInterval;

        /** Fraction each interval is randomized by, so records that failed together do not all return at once. */
        private Double jitter;
    }

    /** Audit settings. */
    @Getter
    @Setter
    public static class Audit {

        /** Whether anything is audited at all. */
        private Boolean enabled;

        /**
         * Whether audit events are written to {@code sync_audit_record} in addition to the log.
         *
         * <p>Off by default. Structured logs answer most questions and cost nothing extra; a persisted
         * trail is for the integrations where "prove what the partner told us, and when" is a question
         * somebody will actually be asked.
         */
        private Boolean persist;
    }

    /** An asynchronous remote job's three schedules and its lifetime rules. */
    @Getter
    @Setter
    public static class Job {

        /** The submit pass: drains demand into new jobs, gated by the quota. */
        @Valid
        private final Submit submit = new Submit();

        /** The poll pass: probes in-flight jobs. */
        @Valid
        private final Poll poll = new Poll();

        /** The collect pass: walks finished jobs' results into staging. */
        @Valid
        private final Collect collect = new Collect();

        /**
         * How long a job may live before it is cancelled, marked expired and its demand requeued.
         *
         * <p>Also the hard stop on the quota lease held for it: a job that will never finish would
         * otherwise hold its slot forever while heartbeating perfectly.
         */
        private Duration maxLifetime;

        /**
         * What to do about a submit whose outcome is unknown. Required for this shape; see
         * {@link AmbiguousSubmitPolicy} for why there is no default.
         */
        private AmbiguousSubmitPolicy onAmbiguousSubmit;

        /**
         * How long a row may sit in {@code PENDING_SUBMIT} before it is considered ambiguous rather
         * than merely in progress. Must comfortably exceed the partner's worst-case submit latency,
         * or a slow submit is resolved as ambiguous while it is still perfectly fine.
         */
        private Duration submitGracePeriod;
    }

    /** The submit pass. */
    @Getter
    @Setter
    public static class Submit {

        /** Delay between submit passes. */
        private Duration fixedDelay;

        /**
         * How many new jobs one pass may start.
         *
         * <p>Distinct from the quota: the quota says how many may be in flight, this says how fast
         * the engine is willing to get there. Starting a partner's whole budget in one tick is how a
         * queue that was about to drain becomes a queue that is full again.
         */
        @Positive
        private Integer maxConcurrentSubmits;
    }

    /** The poll pass. */
    @Getter
    @Setter
    public static class Poll {

        /** Delay between poll passes. */
        private Duration fixedDelay;

        /**
         * How much the interval between probes of one job grows each time it is still running.
         *
         * <p>A job that has been running for two hours is not about to finish in the next thirty
         * seconds, and probing it at the same rate as a job submitted a minute ago spends the
         * partner's rate budget on the least informative question available.
         */
        @Min(1)
        private Double backoffMultiplier;

        /** Ceiling on the per-job probe interval. */
        private Duration maxInterval;

        /**
         * Whether a partner's {@code Retry-After} or completion estimate overrides the computed
         * interval. On by default: a partner that says when to come back has told us something the
         * backoff curve is only guessing at.
         */
        private Boolean honourRetryAfter;
    }

    /** The collect pass. */
    @Getter
    @Setter
    public static class Collect {

        /** Delay between collect passes. */
        private Duration fixedDelay;

        /**
         * How many jobs' results may be collected at once.
         *
         * <p>Separate budget from polling, and usually a much smaller one: probing a hundred running
         * jobs is cheap and frequent, collecting one result is expensive and rare. Sharing a budget
         * means either the probes are throttled to the collections' pace or the collections are given
         * the probes' concurrency, and both are wrong.
         */
        @Positive
        private Integer maxConcurrency;
    }

    /** A partner-scoped concurrency budget. */
    @Getter
    @Setter
    public static class Quota {

        /** How many slots exist across the whole cluster. */
        @Positive
        @NotNull
        private Integer maxConcurrent;

        /** How long a slot stays held without a renewal. */
        @NotNull
        private Duration leaseTtl = Duration.ofMinutes(15);

        /**
         * How often a holder renews. Must be several times shorter than {@code lease-ttl}: one
         * renewal per lease leaves no margin, and a single slow one - a garbage-collection pause, a
         * database hiccup - loses the slot while the work it covers is still running.
         */
        @NotNull
        private Duration heartbeatInterval = Duration.ofMinutes(2);

        /**
         * How long an acquisition attempt waits.
         *
         * <p>Zero by default, and that default is deliberate: a scheduled pass that cannot get a slot
         * has nothing useful to do but come back next tick, and waiting pins a scheduler thread for
         * as long as a saturated partner stays saturated.
         */
        @NotNull
        private Duration acquireTimeout = Duration.ZERO;

        /**
         * Whether slots are granted in arrival order.
         *
         * <p>On by default. Without it, a task on a thirty-second cadence wins essentially every race
         * against a task on a five-minute one, and the slower task can starve indefinitely while every
         * individual acquisition looks correct.
         */
        private boolean fifo = true;

        /** What to do with a lease whose holder stopped heartbeating. */
        @NotNull
        private QuotaReclaimPolicy reclaim = QuotaReclaimPolicy.VERIFY_REMOTE;

        /** Hard stop on a single slot, independent of renewals. */
        @NotNull
        private Duration maxLifetime = Duration.ofHours(6);
    }

    /** A partner-scoped request-rate budget. */
    @Getter
    @Setter
    public static class RateLimit {

        /** Requests allowed per {@code per}. */
        @Positive
        @NotNull
        private Integer permits;

        /** The window {@code permits} applies to. */
        @NotNull
        private Duration per = Duration.ofMinutes(1);

        /**
         * How long a call waits for a permit.
         *
         * <p>Unlike a quota's, this defaults to a short non-zero wait: a rate limit is about pacing
         * within a pass that has already started and already holds a quota slot, and abandoning that
         * pass because a permit was a few hundred milliseconds away wastes the slot.
         */
        @NotNull
        private Duration timeout = Duration.ofSeconds(5);
    }

    /** Whether this module applies its own changelog. */
    @Getter
    @Setter
    public static class Liquibase {

        private boolean enabled = true;
    }

    /** Whether Micrometer instrumentation is registered. */
    @Getter
    @Setter
    public static class Metrics {

        private boolean enabled = true;
    }

    /** The actuator endpoint's own settings. */
    @Getter
    @Setter
    public static class Endpoint {

        /**
         * Whether destructive operations - force-releasing a lease, cancelling a remote job - are
         * available at all.
         *
         * <p>Off by default. Both can make the partner's view and this system's view disagree, and an
         * environment where nobody should be doing that by hand should not offer the button.
         */
        private boolean allowDestructiveOperations;
    }

    /** Recovery of rows and jobs left behind by an instance that died. */
    @Getter
    @Setter
    public static class Reclaim {

        /** How often abandoned claims and expired leases are swept up. */
        @NotNull
        private Duration fixedDelay = Duration.ofMinutes(1);

        /**
         * How long a staged record may sit {@code PROCESSING} before it is presumed abandoned.
         *
         * <p>Must exceed the longest legitimate apply, or the sweeper takes a record away from an
         * instance that is still applying it and a second instance applies it again.
         */
        @NotNull
        private Duration staleRecordTimeout = Duration.ofMinutes(5);
    }
}
