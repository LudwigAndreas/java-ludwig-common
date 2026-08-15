package ru.ludwigandreas.hotreload.core.watch;

import ru.ludwigandreas.hotreload.core.ReloadableSource;
import ru.ludwigandreas.hotreload.core.SourceReloadCoordinator;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Polls a fixed set of {@link ReloadableSource}s on a shared interval and hands each load to a
 * {@link SourceReloadCoordinator} for diffing/notification. This is the only viable reload strategy for
 * sources with no filesystem-level change notification - most notably Vault KV secrets, which expose no
 * push/watch API of their own.
 */
public class PollingResourceWatcher implements ResourceWatcher {

    private final List<? extends ReloadableSource> sources;
    private final SourceReloadCoordinator coordinator;
    private final Duration interval;
    private final Duration initialDelay;

    private final AtomicInteger threadCounter = new AtomicInteger();
    private volatile ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> scheduledFuture;

    public PollingResourceWatcher(List<? extends ReloadableSource> sources,
                                   SourceReloadCoordinator coordinator,
                                   Duration interval,
                                   Duration initialDelay) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Polling interval must be positive, got " + interval);
        }
        this.sources = List.copyOf(sources);
        this.coordinator = coordinator;
        this.interval = interval;
        this.initialDelay = initialDelay;
    }

    @Override
    public synchronized void start() {
        if (isRunning()) {
            return;
        }
        sources.forEach(coordinator::reload);

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "hotreload-poll-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newSingleThreadScheduledExecutor(threadFactory);
        scheduledFuture = executor.scheduleWithFixedDelay(this::pollAll,
                initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return scheduledFuture != null && !scheduledFuture.isDone();
    }

    private void pollAll() {
        for (ReloadableSource source : sources) {
            coordinator.reload(source);
        }
    }
}
