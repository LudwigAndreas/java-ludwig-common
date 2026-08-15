package ru.ludwigandreas.db.core.metrics;

public class NoopDbCoreMetrics implements DbCoreMetrics {

    @Override
    public void recordAuditorResolved(boolean present) {
        // no-op
    }

    @Override
    public void recordSnapshotMutationBlocked(String entityType) {
        // no-op
    }
}
