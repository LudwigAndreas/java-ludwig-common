package ru.ludwigandreas.reconciliation.config;

import ru.ludwigandreas.job.core.backoff.BackoffPolicy;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Merges an enumeration's block over {@code enumeration-defaults} over the built-in values, per
 * field.
 *
 * <p>Written out for the same reason {@link TaskSettingsResolver} is: Spring's binder treats
 * {@code enumeration-defaults} and {@code enumerations.<name>} as two unrelated objects, so a service
 * that set a default page size and overrode only the concurrency of one enumeration would otherwise
 * find the whole {@code walk} block of that enumeration back at the built-in values.
 */
public final class EnumerationSettingsResolver {

    // Built-in values, used when neither the enumeration nor the defaults block says anything.
    static final int DEFAULT_DISCOVERY_PAGE_SIZE = 1_000;
    static final int DEFAULT_PAGE_SIZE = 100;
    static final int DEFAULT_MAX_CONCURRENCY = 2;
    static final int DEFAULT_MAX_SCOPES_PER_RUN = 50;
    static final int DEFAULT_CONFIRM_AFTER_MISSES = 1;
    static final double DEFAULT_MAX_FRACTION = 0.5;
    static final int DEFAULT_MAX_ATTEMPTS = 8;

    private static final Duration DEFAULT_FIXED_DELAY = Duration.ofMinutes(1);
    private static final Duration DEFAULT_RUN_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration DEFAULT_DISCOVERY_DELAY = Duration.ofMinutes(5);
    private static final Duration DEFAULT_FULL_INTERVAL = Duration.ofHours(6);
    private static final Duration DEFAULT_INITIAL_INTERVAL = Duration.ofSeconds(2);
    private static final double DEFAULT_MULTIPLIER = 2.0;
    private static final Duration DEFAULT_MAX_INTERVAL = Duration.ofMinutes(10);
    private static final double DEFAULT_JITTER = 0.3;

    private EnumerationSettingsResolver() {
    }

    /**
     * Resolves every configured enumeration.
     *
     * @param properties the bound configuration
     * @return the resolved settings, keyed by enumeration name, in declaration order
     */
    public static Map<String, EnumerationSettings> resolveAll(ReconciliationProperties properties) {
        Map<String, EnumerationSettings> resolved = new LinkedHashMap<>();
        properties.getEnumerations().forEach((name, enumeration) ->
                resolved.put(name, resolve(name, enumeration, properties.getEnumerationDefaults())));
        return resolved;
    }

    /**
     * Resolves one enumeration.
     *
     * @param name     the enumeration's name
     * @param task     its own block
     * @param defaults the shared defaults
     * @return the resolved settings
     */
    public static EnumerationSettings resolve(String name,
                                              ReconciliationProperties.Enumeration task,
                                              ReconciliationProperties.EnumerationDefaults defaults) {
        ReconciliationProperties.Schedule schedule = task.getSchedule();
        ReconciliationProperties.Schedule defaultSchedule = defaults.getSchedule();
        return new EnumerationSettings(
                name,
                task.isEnabled(),
                resolveSchedule(schedule, defaultSchedule),
                merge(schedule, defaultSchedule, ReconciliationProperties.Schedule::getRunTimeout,
                        DEFAULT_RUN_TIMEOUT),
                ScheduleSpec.fixedDelay(merge(task.getDiscovery(), defaults.getDiscovery(),
                        ReconciliationProperties.Discovery::getFixedDelay, DEFAULT_DISCOVERY_DELAY)),
                merge(task.getDiscovery(), defaults.getDiscovery(),
                        ReconciliationProperties.Discovery::getPageSize, DEFAULT_DISCOVERY_PAGE_SIZE),
                merge(task.getRefresh(), defaults.getRefresh(),
                        ReconciliationProperties.Refresh::getFullInterval, DEFAULT_FULL_INTERVAL),
                merge(task.getRefresh(), defaults.getRefresh(),
                        ReconciliationProperties.Refresh::getIncrementalInterval, null),
                merge(task.getWalk(), defaults.getWalk(),
                        ReconciliationProperties.Walk::getPageSize, DEFAULT_PAGE_SIZE),
                merge(task.getWalk(), defaults.getWalk(),
                        ReconciliationProperties.Walk::getMaxConcurrency, DEFAULT_MAX_CONCURRENCY),
                merge(task.getWalk(), defaults.getWalk(),
                        ReconciliationProperties.Walk::getMaxScopesPerRun, DEFAULT_MAX_SCOPES_PER_RUN),
                merge(task.getSweep(), defaults.getSweep(),
                        ReconciliationProperties.Sweep::getConfirmAfterMisses, DEFAULT_CONFIRM_AFTER_MISSES),
                merge(task.getSweep(), defaults.getSweep(),
                        ReconciliationProperties.Sweep::getMaxFraction, DEFAULT_MAX_FRACTION),
                resolveRetry(task.getRetry(), defaults.getRetry()),
                merge(task.getAudit(), defaults.getAudit(),
                        ReconciliationProperties.Audit::getEnabled, Boolean.TRUE),
                task.getRestClient(),
                task.getRateLimit());
    }

    /**
     * Cron and fixed delay are inherited as a pair, so an enumeration that sets {@code cron} does not
     * pick up a default {@code fixed-delay} and become contradictory.
     */
    private static ScheduleSpec resolveSchedule(ReconciliationProperties.Schedule task,
                                                ReconciliationProperties.Schedule defaults) {
        ReconciliationProperties.Schedule source = hasTiming(task) ? task : defaults;
        if (source != null && source.getCron() != null && !source.getCron().isBlank()) {
            return ScheduleSpec.cron(source.getCron());
        }
        Duration delay = source == null || source.getFixedDelay() == null
                ? DEFAULT_FIXED_DELAY : source.getFixedDelay();
        return ScheduleSpec.fixedDelay(delay,
                merge(task, defaults, ReconciliationProperties.Schedule::getInitialDelay, delay));
    }

    private static boolean hasTiming(ReconciliationProperties.Schedule schedule) {
        if (schedule == null) {
            return false;
        }
        return schedule.getFixedDelay() != null
                || schedule.getCron() != null && !schedule.getCron().isBlank();
    }

    private static TaskSettings.RetrySettings resolveRetry(ReconciliationProperties.Retry task,
                                                           ReconciliationProperties.Retry defaults) {
        return new TaskSettings.RetrySettings(
                merge(task, defaults, ReconciliationProperties.Retry::getMaxAttempts, DEFAULT_MAX_ATTEMPTS),
                new BackoffPolicy(
                        merge(task, defaults, ReconciliationProperties.Retry::getInitialInterval,
                                DEFAULT_INITIAL_INTERVAL),
                        merge(task, defaults, ReconciliationProperties.Retry::getMultiplier,
                                DEFAULT_MULTIPLIER),
                        merge(task, defaults, ReconciliationProperties.Retry::getMaxInterval,
                                DEFAULT_MAX_INTERVAL),
                        merge(task, defaults, ReconciliationProperties.Retry::getJitter, DEFAULT_JITTER)));
    }

    private static <B, V> V merge(B task, B defaults, Function<B, V> field, V builtIn) {
        V fromTask = task == null ? null : field.apply(task);
        if (fromTask != null) {
            return fromTask;
        }
        V fromDefaults = defaults == null ? null : field.apply(defaults);
        return fromDefaults != null ? fromDefaults : builtIn;
    }
}
