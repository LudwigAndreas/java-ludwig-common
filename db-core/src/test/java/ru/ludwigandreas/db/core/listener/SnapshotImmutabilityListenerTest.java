package ru.ludwigandreas.db.core.listener;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.db.core.exception.IntegrityViolationException;
import ru.ludwigandreas.db.core.integration.testmodel.TestSnapshot;
import ru.ludwigandreas.db.core.metrics.DbCoreMetrics;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link SnapshotImmutabilityListener#onPreUpdate} directly (it's package-visible)
 * rather than through a live Hibernate {@code @PreUpdate} callback, so the metrics-recording
 * behavior is verifiable without a database.
 */
class SnapshotImmutabilityListenerTest {

    private static class RecordingMetrics implements DbCoreMetrics {
        final List<String> blockedEntityTypes = new ArrayList<>();

        @Override
        public void recordAuditorResolved(boolean present) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordSnapshotMutationBlocked(String entityType) {
            blockedEntityTypes.add(entityType);
        }
    }

    @Test
    void rejectsTheUpdateAndRecordsAMetricWhenOneIsWired() {
        SnapshotImmutabilityListener listener = new SnapshotImmutabilityListener();
        RecordingMetrics metrics = new RecordingMetrics();
        listener.metrics = metrics;

        TestSnapshot entity = new TestSnapshot("id-1", "kafka", "payload");

        assertThatThrownBy(() -> listener.onPreUpdate(entity))
                .isInstanceOf(IntegrityViolationException.class);
        assertThat(metrics.blockedEntityTypes).containsExactly("TestSnapshot");
    }

    @Test
    void stillRejectsTheUpdateWhenNoMetricsAreWired() {
        SnapshotImmutabilityListener listener = new SnapshotImmutabilityListener();
        TestSnapshot entity = new TestSnapshot("id-1", "kafka", "payload");

        assertThatThrownBy(() -> listener.onPreUpdate(entity))
                .isInstanceOf(IntegrityViolationException.class);
    }
}
