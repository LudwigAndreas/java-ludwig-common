package ru.ludwigandreas.hotreload.metrics;

public class NoopHotReloadMetrics implements HotReloadMetrics {

    @Override
    public void recordReloadSucceeded(String sourceId, int changedKeyCount) {
        // no-op
    }

    @Override
    public void recordReloadFailed(String sourceId, String exceptionType) {
        // no-op
    }
}
