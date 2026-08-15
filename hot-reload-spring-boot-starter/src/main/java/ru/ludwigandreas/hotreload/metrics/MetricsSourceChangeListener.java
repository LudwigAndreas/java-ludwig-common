package ru.ludwigandreas.hotreload.metrics;

import ru.ludwigandreas.hotreload.core.SourceChangeEvent;
import ru.ludwigandreas.hotreload.core.SourceChangeListener;

/** Bridges {@link ru.ludwigandreas.hotreload.core.SourceReloadCoordinator} reload events into {@link HotReloadMetrics}. */
public class MetricsSourceChangeListener implements SourceChangeListener {

    private final HotReloadMetrics metrics;

    public MetricsSourceChangeListener(HotReloadMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void onChange(SourceChangeEvent event) {
        metrics.recordReloadSucceeded(event.sourceId(), event.changedKeys().size());
    }

    @Override
    public void onError(String sourceId, Exception exception) {
        metrics.recordReloadFailed(sourceId, exception.getClass().getSimpleName());
    }
}
