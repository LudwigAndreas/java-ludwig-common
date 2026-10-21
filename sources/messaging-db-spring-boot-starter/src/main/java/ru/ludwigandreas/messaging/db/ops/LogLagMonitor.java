package ru.ludwigandreas.messaging.db.ops;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;
import ru.ludwigandreas.messaging.db.log.LogStore;

/**
 * {@code ludwig.messaging.db.lag}: how many log records each group has not consumed yet, per topic.
 *
 * <p>Refreshed on a schedule rather than computed on scrape, so a scrape never waits on the shared
 * database. During a cutover this is the number that says when the dependency can be removed: zero for
 * every group.
 */
public class LogLagMonitor extends SelfSchedulingLifecycle {

    /** The gauge's name. */
    public static final String METRIC = "ludwig.messaging.db.lag";

    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);

    private final LogStore store;
    private final MultiGauge gauge;

    /**
     * Creates the monitor.
     *
     * @param store         the log
     * @param registry      where the gauge is registered
     * @param taskScheduler the scheduler it runs on
     * @param interval      how often it refreshes
     */
    public LogLagMonitor(LogStore store, MeterRegistry registry, TaskScheduler taskScheduler, Duration interval) {
        super("messaging-db-lag", taskScheduler, ScheduleSpec.fixedDelay(interval, Duration.ZERO), DRAIN_TIMEOUT);
        this.store = store;
        this.gauge = MultiGauge.builder(METRIC).description("Log records a consumer group has not consumed yet")
                .register(registry);
    }

    @Override
    protected void runOnce() {
        gauge.register(store.lag().stream()
                .<MultiGauge.Row<?>>map(lag ->
                        MultiGauge.Row.of(Tags.of("group", lag.groupId(), "topic", lag.topic()), lag.records()))
                .toList(), true);
    }
}
