package ru.ludwigandreas.db.core.metrics;

/**
 * Instrumentation hook for the small set of things in this module worth alerting on operationally.
 * {@link NoopDbCoreMetrics} is always registered as a fallback; {@link MicrometerDbCoreMetrics}
 * replaces it when both Micrometer is on the classpath and {@code ludwig.db.metrics.enabled=true}
 * (the default).
 */
public interface DbCoreMetrics {

    /** {@code AuditorAware} was asked for the current auditor (on every audited entity save). */
    void recordAuditorResolved(boolean present);

    /**
     * A write to an immutable {@link ru.ludwigandreas.db.core.entity.SnapshotEntity} was rejected by
     * {@link ru.ludwigandreas.db.core.listener.SnapshotImmutabilityListener} - worth alerting on, since
     * it means something is trying to mutate data that's supposed to be owned by an external system.
     */
    void recordSnapshotMutationBlocked(String entityType);
}
