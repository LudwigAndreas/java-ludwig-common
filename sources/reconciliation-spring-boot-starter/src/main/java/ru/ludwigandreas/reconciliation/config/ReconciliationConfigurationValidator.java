package ru.ludwigandreas.reconciliation.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.reconciliation.api.Fetcher;
import ru.ludwigandreas.reconciliation.api.ReconciliationTask;
import ru.ludwigandreas.reconciliation.api.SyncTask;
import ru.ludwigandreas.reconciliation.exception.ReconciliationConfigurationException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Refuses to start on a configuration that is individually valid and jointly wrong.
 *
 * <p>Bean Validation checks one field at a time, which catches a negative batch size and misses every
 * interesting mistake in this module. The failures below are all relationships - between two
 * settings, between a setting and a bean, between a declared shape and the interface a fetcher
 * actually implements - and each of them produces behaviour that is intermittent, rare, and nearly
 * impossible to attribute weeks after the change that caused it: an integration that silently never
 * runs, a partner called twice, a quota that admits two holders, a task that fetches nothing forever.
 * Failing the pod at startup is the cheapest possible way to learn about them.
 *
 * <p>Every check states what breaks rather than what is wrong, because the person reading the message
 * is deploying at the time and needs to know whether to roll back.
 */
public class ReconciliationConfigurationValidator {

    /**
     * How much longer a lease must be than the interval that renews it.
     *
     * <p>One renewal per lease leaves no margin: a single slow one - a garbage-collection pause, a
     * database hiccup - loses the slot while the work it covers is still running, and another instance
     * starts a second job against a partner that allowed one.
     */
    private static final int HEARTBEAT_SAFETY_FACTOR = 2;

    private static final Logger log = LoggerFactory.getLogger(ReconciliationConfigurationValidator.class);

    private final ReconciliationProperties properties;
    private final Map<String, TaskSettings> resolved;
    private final List<SyncTask<?, ?, ?>> taskBeans;
    private final Predicate<String> restClientExists;

    /**
     * Creates the validator.
     *
     * @param properties       the bound configuration
     * @param resolved         the merged per-task settings
     * @param taskBeans        every discovered task bean
     * @param restClientExists whether a named REST client is configured; always false when the
     *                         rest-client starter is absent, in which case naming one is itself the
     *                         error
     */
    public ReconciliationConfigurationValidator(ReconciliationProperties properties,
                                                Map<String, TaskSettings> resolved,
                                                List<SyncTask<?, ?, ?>> taskBeans,
                                                Predicate<String> restClientExists) {
        this.properties = properties;
        this.resolved = resolved;
        this.taskBeans = taskBeans;
        this.restClientExists = restClientExists;
    }

    /**
     * Runs every check.
     *
     * @throws ReconciliationConfigurationException listing every problem found, rather than only the
     *                                              first - somebody fixing a configuration file at
     *                                              deploy time should get the whole list in one pass
     */
    @PostConstruct
    public void validate() {
        List<String> problems = new ArrayList<>();
        Map<String, SyncTask<?, ?, ?>> beansByName = indexBeans(problems);

        checkBeansAndConfigurationAgree(beansByName, problems);
        checkQuotas(problems);
        resolved.forEach((name, settings) -> {
            checkReferences(settings, problems);
            checkShape(settings, beansByName.get(name), problems);
            checkAsyncJob(settings, problems);
            checkRunTimeoutAgainstRetryBudget(settings, problems);
        });

        if (!problems.isEmpty()) {
            throw new ReconciliationConfigurationException(problems);
        }
        log.info("Reconciliation configuration validated: {} task(s), {} quota(s), {} rate limit(s)",
                resolved.size(), properties.getQuotas().size(), properties.getRateLimits().size());
    }

    /**
     * Indexes the beans by the name they claim, checking the two places a task states its name agree.
     *
     * <p>A bean whose annotation and {@code name()} disagree would be configured under one and looked
     * up under the other, which presents as a task that exists, starts, and never does anything.
     */
    private Map<String, SyncTask<?, ?, ?>> indexBeans(List<String> problems) {
        Map<String, SyncTask<?, ?, ?>> byName = new LinkedHashMap<>();
        for (SyncTask<?, ?, ?> bean : taskBeans) {
            ReconciliationTask annotation = annotationOf(bean);
            String annotated = annotation == null ? null : annotation.value();
            if (annotated != null && !annotated.equals(bean.name())) {
                problems.add(String.format(
                        "Task bean %s is annotated @ReconciliationTask(\"%s\") but its name() returns "
                                + "\"%s\". It would be configured under one name and looked up under the "
                                + "other, so it would start and never run. Make them the same.",
                        bean.getClass().getName(), annotated, bean.name()));
            }
            SyncTask<?, ?, ?> previous = byName.put(bean.name(), bean);
            if (previous != null) {
                problems.add(String.format(
                        "Two task beans both claim the name \"%s\" (%s and %s). Only one of them would "
                                + "ever be scheduled, and which one is not defined.",
                        bean.name(), previous.getClass().getName(), bean.getClass().getName()));
            }
        }
        return byName;
    }

    private void checkBeansAndConfigurationAgree(Map<String, SyncTask<?, ?, ?>> beansByName,
                                                 List<String> problems) {
        Set<String> configured = new TreeSet<>(resolved.keySet());
        Set<String> implemented = new TreeSet<>(beansByName.keySet());

        for (String name : implemented) {
            if (!configured.contains(name)) {
                problems.add(String.format(
                        "Task bean %s implements task \"%s\", which has no block under "
                                + "ludwig.reconciliation.tasks. It would never be scheduled, and nothing "
                                + "would say so. Configured tasks: %s",
                        beansByName.get(name).getClass().getName(), name, orNone(configured)));
            }
        }
        for (String name : configured) {
            if (!implemented.contains(name)) {
                problems.add(String.format(
                        "Task \"%s\" is configured but no @ReconciliationTask bean implements it, so "
                                + "every setting under it is inert. Implemented tasks: %s",
                        name, orNone(implemented)));
            }
        }
    }

    /**
     * Checks a task's references to shared resources.
     *
     * <p>All three failures look the same at runtime and none of them throws: a task pointed at a
     * quota that does not exist is simply ungated, which is discovered when the partner complains.
     */
    private void checkReferences(TaskSettings settings, List<String> problems) {
        settings.quotaName().ifPresent(quota -> {
            if (!properties.getQuotas().containsKey(quota)) {
                problems.add(String.format(
                        "Task \"%s\" references quota \"%s\", which is not configured. The task would "
                                + "run with no concurrency limit at all against that partner. "
                                + "Configured quotas: %s",
                        settings.name(), quota, orNone(properties.getQuotas().keySet())));
            }
        });
        settings.rateLimitName().ifPresent(limit -> {
            if (!properties.getRateLimits().containsKey(limit)) {
                problems.add(String.format(
                        "Task \"%s\" references rate limit \"%s\", which is not configured. The task "
                                + "would call the partner as fast as its concurrency allows. "
                                + "Configured rate limits: %s",
                        settings.name(), limit, orNone(properties.getRateLimits().keySet())));
            }
        });
        settings.restClientName().ifPresent(client -> {
            if (!restClientExists.test(client)) {
                problems.add(String.format(
                        "Task \"%s\" names REST client \"%s\", which is not configured under "
                                + "ludwig.rest-client.clients (or the rest-client starter is not on the "
                                + "classpath). The task's fetcher would fail on its first call, and only "
                                + "then.",
                        settings.name(), client));
            }
        });
    }

    /**
     * Checks the declared shape against the interface the fetcher actually implements.
     *
     * <p>A disagreement here is the quietest failure in the module: the engine walks the stream the
     * configuration describes, the fetcher answers the questions its interface describes, and a task
     * whose {@code shape: batched} is implemented as a {@code PerItem} fetcher simply never fetches
     * anything, forever, while reporting successful runs.
     */
    private void checkShape(TaskSettings settings, SyncTask<?, ?, ?> bean, List<String> problems) {
        if (bean == null) {
            return;
        }
        Fetcher<?, ?> fetcher = bean.fetcher();
        FetchShape declared = settings.fetch().shape();
        FetchShape actual = shapeOf(fetcher);
        if (actual == null) {
            problems.add(String.format(
                    "Task \"%s\" has a fetcher (%s) that implements none of the four Fetcher shapes.",
                    settings.name(), fetcher.getClass().getName()));
            return;
        }
        if (actual != declared) {
            problems.add(String.format(
                    "Task \"%s\" is configured as shape %s but its fetcher %s implements %s. The engine "
                            + "would walk the stream one way while the fetcher answers the other, and the "
                            + "task would fetch nothing while reporting successful runs.",
                    settings.name(), yaml(declared), fetcher.getClass().getName(), yaml(actual)));
        }
    }

    /**
     * Checks the settings that are required for, and only for, the asynchronous-job shape.
     *
     * <p>{@code on-ambiguous-submit} has no safe default across partners: assuming a submit went
     * through costs a wasted slot and a late sync, and assuming it did not costs a duplicate remote
     * job that the partner may run, charge for, and not deduplicate. Which cost is acceptable is a
     * property of the integration, so the integration has to say.
     */
    private void checkAsyncJob(TaskSettings settings, List<String> problems) {
        if (settings.fetch().shape() != FetchShape.ASYNC_JOB) {
            return;
        }
        Optional<TaskSettings.JobSettings> job = settings.jobOrEmpty();
        if (job.isEmpty()) {
            problems.add(String.format(
                    "Task \"%s\" uses shape async-job but has no job block. Its submit, poll and collect "
                            + "passes have no schedules and it would never run.", settings.name()));
            return;
        }
        if (job.get().onAmbiguousSubmit() == null) {
            problems.add(String.format(
                    "Task \"%s\" uses shape async-job but does not set job.on-ambiguous-submit. When a "
                            + "submit's outcome is unknown, assuming it went through costs a held slot "
                            + "and a late sync; assuming it did not costs a duplicate remote job the "
                            + "partner may bill for. There is no safe default - set "
                            + "assume-submitted or resubmit.", settings.name()));
        }
        if (job.get().maxLifetime().compareTo(job.get().pollMaxInterval()) <= 0) {
            problems.add(String.format(
                    "Task \"%s\" has job.max-lifetime (%s) no longer than job.poll.max-interval (%s), so "
                            + "a job could expire before it is ever probed again.",
                    settings.name(), job.get().maxLifetime(), job.get().pollMaxInterval()));
        }
    }

    /**
     * Checks that a run is allowed to take as long as its own retry budget can make it take.
     *
     * <p>If it is not, the stale-run reclaimer hands the task to a second instance while the first is
     * still working through its retries, and both talk to the partner at once - which is exactly the
     * thing the run lock exists to prevent, defeated by two numbers that are individually reasonable.
     */
    private void checkRunTimeoutAgainstRetryBudget(TaskSettings settings, List<String> problems) {
        Duration budget = settings.retry().worstCaseDuration();
        if (settings.runTimeout().compareTo(budget) < 0) {
            problems.add(String.format(
                    "Task \"%s\" has schedule.run-timeout (%s) shorter than its own retry budget (%s: "
                            + "%d attempts backing off to %s). The run lock would expire while the run "
                            + "is still retrying, and a second instance would start the same run against "
                            + "the same partner. Raise the timeout, or lower retry.max-attempts or "
                            + "retry.max-interval.",
                    settings.name(), settings.runTimeout(), budget,
                    settings.retry().maxAttempts(), settings.retry().backoff().maxInterval()));
        }
    }

    /** Checks each quota's internal consistency. */
    private void checkQuotas(List<String> problems) {
        properties.getQuotas().forEach((name, quota) -> {
            Duration required = quota.getHeartbeatInterval().multipliedBy(HEARTBEAT_SAFETY_FACTOR);
            if (quota.getLeaseTtl().compareTo(required) < 0) {
                problems.add(String.format(
                        "Quota \"%s\" has lease-ttl (%s) shorter than %dx heartbeat-interval (%s). A "
                                + "single slow renewal would lose the slot while the work it covers is "
                                + "still running, and another instance would start a second job against "
                                + "a partner that allows one.",
                        name, quota.getLeaseTtl(), HEARTBEAT_SAFETY_FACTOR, quota.getHeartbeatInterval()));
            }
            if (quota.getMaxLifetime().compareTo(quota.getLeaseTtl()) < 0) {
                problems.add(String.format(
                        "Quota \"%s\" has max-lifetime (%s) shorter than lease-ttl (%s), so a slot would "
                                + "be force-reclaimed before its first renewal was even due.",
                        name, quota.getMaxLifetime(), quota.getLeaseTtl()));
            }
            if (quota.getReclaim() == QuotaReclaimPolicy.ON_EXPIRY) {
                log.warn("Quota '{}' uses reclaim: on-expiry. An expired lease will be taken back "
                        + "without asking the partner whether the work stopped, so the number of jobs "
                        + "actually running there can exceed max-concurrent ({}). Document this where "
                        + "the integration is described.", name, quota.getMaxConcurrent());
            }
        });
    }

    private static FetchShape shapeOf(Fetcher<?, ?> fetcher) {
        // Checked before Paged: a job fetcher is allowed to be paged-like, and the async-job shape is
        // the more specific answer when both are true.
        if (fetcher instanceof Fetcher.JobFetcher) {
            return FetchShape.ASYNC_JOB;
        }
        if (fetcher instanceof Fetcher.Paged) {
            return FetchShape.PAGED;
        }
        if (fetcher instanceof Fetcher.Batched) {
            return FetchShape.BATCHED;
        }
        if (fetcher instanceof Fetcher.PerItem) {
            return FetchShape.PER_ITEM;
        }
        return null;
    }

    private static ReconciliationTask annotationOf(SyncTask<?, ?, ?> bean) {
        return org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                org.springframework.aop.support.AopUtils.getTargetClass(bean), ReconciliationTask.class);
    }

    /** Renders an enum the way it appears in a YAML file, so the message matches what the reader will type. */
    private static String yaml(FetchShape shape) {
        return shape.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    private static String orNone(java.util.Collection<String> names) {
        return names.isEmpty() ? "(none)" : String.join(", ", names);
    }
}
