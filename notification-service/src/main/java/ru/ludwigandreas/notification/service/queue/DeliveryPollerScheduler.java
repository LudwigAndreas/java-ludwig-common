package ru.ludwigandreas.notification.service.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;

/**
 * Drives {@link DeliveryDispatchService#processCycle()} on a fixed delay, and drains cleanly.
 *
 * <p>Scheduled against an injected {@link TaskScheduler} rather than with {@code @Scheduled}, exactly
 * as the outbox module's {@code OutboxPublisherScheduler} is: {@code @Scheduled} takes its interval
 * as a string attribute, which would mean re-parsing a {@link Duration} that
 * {@code NotificationProperties} has already bound and validated, and it would require the
 * application to remember {@code @EnableScheduling}. The service works without either.
 *
 * <h2>Draining</h2>
 *
 * <p>The requirement is that a shutdown leaves no row {@code CLAIMED}. Three things together achieve
 * that, and all three are needed.
 *
 * <p>{@link #stop()} cancels the schedule without interrupting a cycle that is already running -
 * {@code cancel(false)}, because interrupting mid-send would abandon a message whose provider call
 * may already have succeeded, and the delivery would be sent twice on the retry. It then waits for
 * that cycle to finish, bounded, so a hung provider cannot hold the pod open past its termination
 * grace period.
 *
 * <p>Whatever leases remain - because the wait timed out, or because the cycle stopped between the
 * claim and the send - are handed back explicitly, so a rolling deploy costs no latency.
 *
 * <p>And the stale sweeper is the backstop for the case none of this covers: a {@code SIGKILL},
 * where no shutdown hook runs at all.
 */
@Slf4j
public class DeliveryPollerScheduler implements SmartLifecycle {

    /**
     * Runs late in startup and stops early in shutdown.
     *
     * <p>Below {@code DEFAULT_PHASE} so this stops before the web server and the connection pool do:
     * a cycle still dispatching while the datasource is closing cannot record its outcomes, and every
     * delivery it sent would be sent again.
     */
    private static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 1024;

    private final DeliveryDispatchService dispatchService;
    private final DeliveryClaimService claimService;
    private final TaskScheduler taskScheduler;
    private final Duration initialDelay;
    private final Duration pollInterval;
    private final Duration drainTimeout;

    /** Held for the duration of a cycle, so {@link #stop()} can wait for one to finish. */
    private final Semaphore cycleInProgress = new Semaphore(1);

    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * Not final because it is assigned on every {@link #start()}.
     *
     * <p>Volatile rather than merely mutable: {@link #stop()} runs on the shutdown thread and must
     * see what the startup thread wrote, which without the barrier it is not guaranteed to.
     */
    private volatile ScheduledFuture<?> scheduledFuture;

    public DeliveryPollerScheduler(DeliveryDispatchService dispatchService,
                                   DeliveryClaimService claimService,
                                   TaskScheduler taskScheduler,
                                   Duration initialDelay,
                                   Duration pollInterval,
                                   Duration drainTimeout) {
        this.dispatchService = dispatchService;
        this.claimService = claimService;
        this.taskScheduler = taskScheduler;
        this.initialDelay = initialDelay;
        this.pollInterval = pollInterval;
        this.drainTimeout = drainTimeout;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduledFuture = taskScheduler.scheduleWithFixedDelay(
                this::poll, Instant.now().plus(initialDelay), pollInterval);
        log.info("Delivery poller started (every {}, first run in {})", pollInterval, initialDelay);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ScheduledFuture<?> future = scheduledFuture;
        if (future != null) {
            // false: never interrupt a cycle mid-send. A provider call that has already been made
            // cannot be un-made, and abandoning it without recording the outcome guarantees a
            // duplicate on the retry - which is exactly what this whole service works to avoid.
            future.cancel(false);
        }
        scheduledFuture = null;
        awaitCycleCompletion();
        releaseRemainingLeases();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    private void awaitCycleCompletion() {
        try {
            if (cycleInProgress.tryAcquire(drainTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                cycleInProgress.release();
                log.info("Delivery poller drained cleanly");
                return;
            }
            log.warn("Delivery poller did not finish its cycle within {}; releasing its leases anyway",
                    drainTimeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while draining the delivery poller");
        }
    }

    /**
     * Hands back leases this instance still holds.
     *
     * <p>Only rows still {@code CLAIMED} are touched - the repository predicate says so - so a
     * delivery the cycle settled between the timeout and this call is not dragged back to
     * {@code PENDING} and sent a second time.
     */
    private void releaseRemainingLeases() {
        Set<UUID> leases = dispatchService.inFlightLeases();
        if (leases.isEmpty()) {
            return;
        }
        try {
            long released = claimService.release(leases);
            log.info("Released {} delivery lease(s) on shutdown", released);
        } catch (RuntimeException e) {
            // The datasource may already be closing. The stale sweeper on another replica will
            // recover these within one lease period, so this is a latency cost rather than a loss.
            log.warn("Could not release {} delivery lease(s) on shutdown; the stale sweeper will "
                    + "recover them", leases.size(), e);
        }
    }

    private void poll() {
        if (!cycleInProgress.tryAcquire()) {
            // The previous cycle is still running. scheduleWithFixedDelay already prevents overlap on
            // this scheduler, so reaching here means something else triggered a poll; skipping is
            // right, because two concurrent cycles on one instance would each claim a batch and
            // double this instance's in-flight leases.
            return;
        }
        try {
            int dispatched = dispatchService.processCycle();
            if (dispatched > 0) {
                log.debug("Dispatched {} delivery/deliveries", dispatched);
            }
        } catch (RuntimeException e) {
            // A poll cycle must never die. The scheduler cancels a repeating task whose run throws,
            // so an unhandled exception here would silently stop the queue for the lifetime of the
            // pod - the failure mode that looks like "notifications just stopped".
            log.error("Delivery poll cycle failed", e);
        } finally {
            cycleInProgress.release();
        }
    }
}
