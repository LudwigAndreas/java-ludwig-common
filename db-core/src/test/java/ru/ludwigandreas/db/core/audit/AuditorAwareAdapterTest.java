package ru.ludwigandreas.db.core.audit;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.db.core.metrics.DbCoreMetrics;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AuditorAwareAdapterTest {

    private static class RecordingMetrics implements DbCoreMetrics {
        final List<Boolean> auditorResolutions = new java.util.ArrayList<>();

        @Override
        public void recordAuditorResolved(boolean present) {
            auditorResolutions.add(present);
        }

        @Override
        public void recordSnapshotMutationBlocked(String entityType) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void delegatesToTheProviderAndReturnsItsResult() {
        RecordingMetrics metrics = new RecordingMetrics();
        AuditorAwareAdapter<String> adapter = new AuditorAwareAdapter<>(() -> Optional.of("alice"), metrics);

        assertThat(adapter.getCurrentAuditor()).contains("alice");
    }

    @Test
    void recordsAuditorPresentWhenTheProviderReturnsAValue() {
        RecordingMetrics metrics = new RecordingMetrics();
        AuditorAwareAdapter<String> adapter = new AuditorAwareAdapter<>(() -> Optional.of("alice"), metrics);

        adapter.getCurrentAuditor();

        assertThat(metrics.auditorResolutions).containsExactly(true);
    }

    @Test
    void recordsAuditorAbsentWhenTheProviderReturnsEmpty() {
        RecordingMetrics metrics = new RecordingMetrics();
        AuditorAwareAdapter<String> adapter = new AuditorAwareAdapter<>(Optional::empty, metrics);

        adapter.getCurrentAuditor();

        assertThat(metrics.auditorResolutions).containsExactly(false);
    }
}
