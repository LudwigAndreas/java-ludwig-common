package ru.ludwigandreas.reconciliation.engine.enumeration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.ScheduledJob;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;
import ru.ludwigandreas.reconciliation.config.EnumerationSettings;
import ru.ludwigandreas.reconciliation.entity.ScopeMemberStatus;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.repository.SyncScopeMemberRepository;
import ru.ludwigandreas.reconciliation.repository.SyncScopeStateRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Schedules every enumeration's passes and owns their lifecycle.
 *
 * <p>The sibling of {@code ReconciliationRuntime}, and separate from it so that class's public
 * constructor does not change for the services that replace it. Each enabled enumeration gets a
 * {@code <name>:discover} pass and a {@code <name>:walk} pass, on the scheduler the module already
 * owns - never {@code @Scheduled}, which only works when the consuming service remembered
 * {@code @EnableScheduling} and fails silently when it did not.
 */
public class EnumerationRuntime implements SmartLifecycle {

    private static final double MILLIS_PER_SECOND = 1000.0;
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(20);

    private static final Logger log = LoggerFactory.getLogger(EnumerationRuntime.class);

    private final EnumerationRegistry registry;
    private final EnumerationRunner runner;
    private final SyncScopeStateRepository states;
    private final SyncScopeMemberRepository members;
    private final ReconciliationMetrics metrics;
    private final TaskScheduler scheduler;
    private final Map<String, ScheduledJob> jobs = new LinkedHashMap<>();
    private volatile boolean running;

    /**
     * Creates the runtime.
     *
     * @param registry  the enumerations
     * @param runner    runs one pass
     * @param states    scope state, for the gauges
     * @param members   membership, for the gauges
     * @param metrics   instrumentation
     * @param scheduler the scheduler every pass registers itself with
     */
    public EnumerationRuntime(EnumerationRegistry registry,
                              EnumerationRunner runner,
                              SyncScopeStateRepository states,
                              SyncScopeMemberRepository members,
                              ReconciliationMetrics metrics,
                              TaskScheduler scheduler) {
        this.registry = registry;
        this.runner = runner;
        this.states = states;
        this.members = members;
        this.metrics = metrics;
        this.scheduler = scheduler;
    }

    @Override
    public void start() {
        registry.all().forEach(this::schedule);
        registry.all().forEach(this::registerGauges);
        jobs.values().forEach(ScheduledJob::start);
        running = true;
        if (!jobs.isEmpty()) {
            log.info("Reconciliation started {} scheduled pass(es) across {} enumeration(s)",
                    jobs.size(), registry.all().size());
        }
    }

    @Override
    public void stop() {
        List<ScheduledJob> ordered = new ArrayList<>(jobs.values());
        Collections.reverse(ordered);
        ordered.forEach(ScheduledJob::stop);
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return SelfSchedulingLifecycle.DEFAULT_PHASE;
    }

    /**
     * Runs a pass now, on the calling thread.
     *
     * @param passName {@code <enumeration>:discover} or {@code <enumeration>:walk}
     * @return false if that pass was already running
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

    /**
     * How far an enumeration's most overdue scope is past its due time.
     *
     * <p>Measured from when each scope was last listed, not from when it is next scheduled: a scope
     * whose walks keep failing is pushed back by its backoff, and a gauge reading the schedule would
     * report it as on time for exactly as long as it stayed broken. A scope that has never been
     * listed is late from the moment it was registered.
     *
     * <p>Pulled rather than set from inside a walk, so an enumeration that has stopped running
     * altogether reports a lag that climbs instead of its last healthy value. Zero while every scope
     * is within the task's interval, and zero for an enumeration with no scopes yet.
     *
     * @param enumeration the enumeration's name
     * @return the lag in seconds, or empty if there is no such enumeration
     */
    public Optional<Double> freshnessLagSeconds(String enumeration) {
        return registry.find(enumeration).map(found -> lagSeconds(found.settings()));
    }

    private double lagSeconds(EnumerationSettings settings) {
        Instant now = Instant.now();
        // The interval a scope is held to: the incremental one where the task is incremental,
        // because that is the cadence it promises, and the full one otherwise.
        Duration interval = settings.incrementalIntervalOrEmpty().orElse(settings.fullInterval());
        double listed = states.findOldestListing(settings.name())
                .map(last -> secondsBetween(last.plus(interval), now)).orElse(0.0);
        double neverListed = states.findOldestNeverListed(settings.name())
                .map(registered -> secondsBetween(registered, now)).orElse(0.0);
        return Math.max(0.0, Math.max(listed, neverListed));
    }

    private static double secondsBetween(Instant from, Instant to) {
        return Duration.between(from, to).toMillis() / MILLIS_PER_SECOND;
    }

    private void schedule(RegisteredEnumeration<?, ?, ?, ?> enumeration) {
        EnumerationSettings settings = enumeration.settings();
        if (!settings.enabled()) {
            log.info("Enumeration '{}' is disabled; nothing is scheduled for it", settings.name());
            return;
        }
        add(settings.name() + ":discover", settings.discoverySchedule(),
                () -> runner.discoverPass(enumeration));
        add(settings.name() + ":walk", settings.schedule(), () -> runner.walkPass(enumeration));
    }

    private void add(String name, ScheduleSpec schedule, Runnable work) {
        jobs.put(name, new ScheduledJob(name, scheduler, schedule, DRAIN_TIMEOUT, work));
    }

    private void registerGauges(RegisteredEnumeration<?, ?, ?, ?> enumeration) {
        String name = enumeration.name();
        metrics.registerScopeFreshnessLag(name, () -> lagSeconds(enumeration.settings()));
        metrics.registerScopeAttention(name,
                () -> members.countByStatus(name, ScopeMemberStatus.QUARANTINED),
                () -> states.countHeldSweeps(name));
    }
}
