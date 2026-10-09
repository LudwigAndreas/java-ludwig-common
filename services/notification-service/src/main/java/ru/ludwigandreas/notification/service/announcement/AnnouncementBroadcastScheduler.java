package ru.ludwigandreas.notification.service.announcement;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.notification.service.model.BroadcastRunView;
import ru.ludwigandreas.notification.service.lock.LockNames;
import ru.ludwigandreas.notification.settings.NotificationProperties;

/**
 * Drives announcement email fan-outs, one batch at a time, on one replica.
 *
 * <p>Under {@code job-core}'s leased {@link RunLock}, like the digest and retention jobs, so three
 * replicas do not each walk the same audience. That is not an optimisation: the delivery dedup key
 * would stop the duplicate sends, but each replica would still count them, and a run reporting three
 * times as many deliveries as it created is worse than a slow one.
 *
 * <p>The lease is renewed <b>between batches</b> and a lost lease stops the run - which is safe
 * precisely because each batch commits its deliveries and its cursor together, so stopping between
 * two of them leaves nothing half-done. The next cycle, here or on another replica, resumes from the
 * recorded cursor.
 *
 * <p>Bounded per cycle rather than looping until the audience is exhausted. A hundred-thousand-person
 * broadcast would otherwise hold the lock for as long as it took, and the lease exists to bound
 * exactly that: a pod that died mid-broadcast would block every other announcement until the lease
 * lapsed.
 */
@Slf4j
public class AnnouncementBroadcastScheduler implements SmartLifecycle {

    /**
     * Batches per cycle. Small on purpose - the point of a cycle is to make progress and then let go
     * of the lock, not to finish.
     */
    private static final int BATCHES_PER_CYCLE = 20;

    /** Runs advanced per cycle, so one very large broadcast cannot starve the others entirely. */
    private static final int RUNS_PER_CYCLE = 5;

    private final RunLock runLock;
    private final AnnouncementEmailBroadcastService broadcastService;
    private final TaskScheduler taskScheduler;
    private final NotificationProperties properties;

    private volatile ScheduledFuture<?> scheduledFuture;

    public AnnouncementBroadcastScheduler(RunLock runLock,
                                          AnnouncementEmailBroadcastService broadcastService,
                                          TaskScheduler taskScheduler,
                                          NotificationProperties properties) {
        this.runLock = runLock;
        this.broadcastService = broadcastService;
        this.taskScheduler = taskScheduler;
        this.properties = properties;
    }

    @Override
    public void start() {
        Duration interval = properties.getAnnouncements().getFanOut().getRunInterval();
        scheduledFuture = taskScheduler.scheduleWithFixedDelay(
                this::run, Instant.now().plus(interval), interval);
        log.info("Announcement email fan-out scheduled every {}", interval);
    }

    @Override
    public void stop() {
        ScheduledFuture<?> future = scheduledFuture;
        if (future != null) {
            future.cancel(false);
        }
        scheduledFuture = null;
    }

    @Override
    public boolean isRunning() {
        ScheduledFuture<?> future = scheduledFuture;
        return future != null && !future.isDone();
    }

    private void run() {
        try {
            runLock.runIfAvailable(LockNames.ANNOUNCEMENT_BROADCAST, this::advance);
        } catch (RuntimeException e) {
            log.error("Announcement email fan-out run failed", e);
        }
    }

    /**
     * Advances every claimable run by a bounded number of batches.
     *
     * <p>Exposed package-private rather than private so an integration test can drive it directly
     * instead of waiting for a tick - the same arrangement every other scheduled job in this service
     * uses, and for the same reason: a test that slept until a poller happened to run would be slow
     * when it passed and flaky when it failed.
     */
    void advance(RunLockHandle lock) {
        for (BroadcastRunView run : broadcastService.claimable(RUNS_PER_CYCLE)) {
            for (int batch = 0; batch < BATCHES_PER_CYCLE; batch++) {
                if (!lock.renew(runLock.defaultLeaseTtl())) {
                    log.warn("Announcement fan-out stopped part way: the lock was lost");
                    return;
                }
                if (!broadcastService.advanceOneBatch(run.id())) {
                    break;
                }
            }
        }
    }

    /** Drives one cycle without the lock, for tests. */
    public void runOneCycleForTesting() {
        runLock.runIfAvailable(LockNames.ANNOUNCEMENT_BROADCAST, this::advance);
    }
}
