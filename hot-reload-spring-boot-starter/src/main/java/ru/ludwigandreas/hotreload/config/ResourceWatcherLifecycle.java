package ru.ludwigandreas.hotreload.config;

import org.springframework.context.SmartLifecycle;
import ru.ludwigandreas.hotreload.core.watch.ResourceWatcher;

/** Adapts any {@link ResourceWatcher} to the Spring bean lifecycle, so it starts/stops with the context. */
public class ResourceWatcherLifecycle implements SmartLifecycle {

    private final ResourceWatcher watcher;

    public ResourceWatcherLifecycle(ResourceWatcher watcher) {
        this.watcher = watcher;
    }

    @Override
    public void start() {
        watcher.start();
    }

    @Override
    public void stop() {
        watcher.stop();
    }

    @Override
    public boolean isRunning() {
        return watcher.isRunning();
    }
}
