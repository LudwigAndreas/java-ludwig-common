package ru.ludwigandreas.hotreload.metrics;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.hotreload.core.SourceChangeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsSourceChangeListenerTest {

    private record SucceededCall(String sourceId, int changedKeyCount) {
    }

    private record FailedCall(String sourceId, String exceptionType) {
    }

    private static class RecordingMetrics implements HotReloadMetrics {
        final List<SucceededCall> succeeded = new ArrayList<>();
        final List<FailedCall> failed = new ArrayList<>();

        @Override
        public void recordReloadSucceeded(String sourceId, int changedKeyCount) {
            succeeded.add(new SucceededCall(sourceId, changedKeyCount));
        }

        @Override
        public void recordReloadFailed(String sourceId, String exceptionType) {
            failed.add(new FailedCall(sourceId, exceptionType));
        }
    }

    @Test
    void onChangeRecordsTheChangedKeyCount() {
        RecordingMetrics metrics = new RecordingMetrics();
        MetricsSourceChangeListener listener = new MetricsSourceChangeListener(metrics);

        listener.onChange(new SourceChangeEvent("file:/etc/app.properties",
                Map.of("a", "1", "b", "2"), Map.of(), Set.of("a", "b")));

        assertThat(metrics.succeeded).containsExactly(new SucceededCall("file:/etc/app.properties", 2));
        assertThat(metrics.failed).isEmpty();
    }

    @Test
    void onErrorRecordsTheExceptionSimpleName() {
        RecordingMetrics metrics = new RecordingMetrics();
        MetricsSourceChangeListener listener = new MetricsSourceChangeListener(metrics);

        listener.onError("vault:secret/myapp", new IllegalStateException("vault unreachable"));

        assertThat(metrics.failed).containsExactly(new FailedCall("vault:secret/myapp", "IllegalStateException"));
        assertThat(metrics.succeeded).isEmpty();
    }
}
