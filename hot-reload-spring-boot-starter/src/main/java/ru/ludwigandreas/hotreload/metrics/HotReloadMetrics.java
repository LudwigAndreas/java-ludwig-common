package ru.ludwigandreas.hotreload.metrics;

/**
 * Instrumentation hook for the reload engine. {@link NoopHotReloadMetrics} is always registered as a
 * fallback; {@link MicrometerHotReloadMetrics} replaces it when both Micrometer is on the classpath and
 * {@code ludwig.hotreload.metrics.enabled=true} (the default).
 */
public interface HotReloadMetrics {

    /** A source (file or Vault secret) reloaded successfully. */
    void recordReloadSucceeded(String sourceId, int changedKeyCount);

    /**
     * A reload attempt failed - the previous, valid content kept serving (see {@code
     * SourceReloadCoordinator}). Worth alerting on: it means a source is stuck on stale content.
     */
    void recordReloadFailed(String sourceId, String exceptionType);
}
