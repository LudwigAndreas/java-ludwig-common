package ru.ludwigandreas.hotreload.core.watch;

/**
 * Lifecycle for something that keeps one or more {@link ru.ludwigandreas.hotreload.core.ReloadableSource}s
 * fresh - either by watching for change notifications ({@link FileResourceWatcher}) or by polling on an
 * interval ({@link PollingResourceWatcher}). {@link #start()} performs an initial synchronous load of
 * every source before returning, so content is available immediately rather than only after the first
 * watch/poll tick.
 */
public interface ResourceWatcher {

    void start();

    void stop();

    boolean isRunning();
}
