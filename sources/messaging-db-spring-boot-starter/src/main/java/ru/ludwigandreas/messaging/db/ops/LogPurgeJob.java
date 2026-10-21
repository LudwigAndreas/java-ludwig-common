package ru.ludwigandreas.messaging.db.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;
import ru.ludwigandreas.messaging.db.log.LogStore;

/**
 * Deletes records older than the retention, consumed or not.
 *
 * <p>Unconsumed records are purged deliberately: the log lives in a database every service shares, and a
 * group that stopped consuming must not grow it without bound. What a group loses is logged per topic
 * first, which is the signal that it stopped.
 */
@Slf4j
public class LogPurgeJob extends SelfSchedulingLifecycle {

    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);

    private final LogStore store;
    private final Duration retention;
    private final Clock clock;

    /**
     * Creates the job.
     *
     * @param store         the log
     * @param taskScheduler the scheduler it runs on
     * @param retention     how long a record is kept
     * @param interval      how often it runs
     * @param clock         where "now" comes from
     */
    public LogPurgeJob(LogStore store, TaskScheduler taskScheduler, Duration retention, Duration interval,
                       Clock clock) {
        super("messaging-db-purge", taskScheduler, ScheduleSpec.fixedDelay(interval), DRAIN_TIMEOUT);
        this.store = store;
        this.retention = retention;
        this.clock = clock;
    }

    @Override
    protected void runOnce() {
        Instant cutoff = clock.instant().minus(retention);
        for (LogStore.Lag lost : store.unconsumedBefore(cutoff)) {
            log.warn("Purging {} record(s) of topic '{}' that group '{}' never consumed (older than {})",
                    lost.records(), lost.topic(), lost.groupId(), retention);
        }
        long deleted = store.purgeBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} log record(s) produced before {}", deleted, cutoff);
        }
    }
}
