package ru.ludwigandreas.hotreload.audit;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.hotreload.core.SourceChangeEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuditingSourceChangeListenerTest {

    private static class RecordingAuditLogger implements HotReloadAuditLogger {
        final List<HotReloadAuditEntry> entries = new ArrayList<>();
        final List<Exception> errors = new ArrayList<>();

        @Override
        public void record(HotReloadAuditEntry entry) {
            entries.add(entry);
        }

        @Override
        public void recordError(String sourceId, Instant timestamp, String actor, Exception exception) {
            errors.add(exception);
        }
    }

    @Test
    void recordsOldAndNewValuesForEveryChangedKey() {
        RecordingAuditLogger logger = new RecordingAuditLogger();
        AuditingSourceChangeListener listener = new AuditingSourceChangeListener(logger, "instance-1");

        listener.onChange(new SourceChangeEvent("file:/etc/app.properties",
                Map.of("app.name", "v2"), Map.of("app.name", "v1"), Set.of("app.name")));

        assertThat(logger.entries).hasSize(1);
        HotReloadAuditEntry entry = logger.entries.get(0);
        assertThat(entry.sourceId()).isEqualTo("file:/etc/app.properties");
        assertThat(entry.actor()).isEqualTo("instance-1");
        assertThat(entry.changes()).containsEntry("app.name", new HotReloadAuditEntry.ValueChange("v1", "v2"));
    }

    @Test
    void redactsSensitiveValuesBeforeTheyReachTheLogger() {
        RecordingAuditLogger logger = new RecordingAuditLogger();
        AuditingSourceChangeListener listener = new AuditingSourceChangeListener(logger, "instance-1");

        listener.onChange(new SourceChangeEvent("vault:secret/myapp",
                Map.of("db.password", "newSecret"), Map.of("db.password", "oldSecret"), Set.of("db.password")));

        HotReloadAuditEntry.ValueChange change = logger.entries.get(0).changes().get("db.password");
        assertThat(change.oldValue()).isEqualTo(SecretRedaction.REDACTED);
        assertThat(change.newValue()).isEqualTo(SecretRedaction.REDACTED);
    }

    @Test
    void doesNothingWhenThereAreNoChangedKeys() {
        RecordingAuditLogger logger = new RecordingAuditLogger();
        AuditingSourceChangeListener listener = new AuditingSourceChangeListener(logger, "instance-1");

        listener.onChange(new SourceChangeEvent("file:/etc/app.properties", Map.of(), Map.of(), Set.of()));

        assertThat(logger.entries).isEmpty();
    }

    @Test
    void forwardsErrorsToTheLogger() {
        RecordingAuditLogger logger = new RecordingAuditLogger();
        AuditingSourceChangeListener listener = new AuditingSourceChangeListener(logger, "instance-1");

        RuntimeException failure = new RuntimeException("boom");
        listener.onError("file:/etc/app.properties", failure);

        assertThat(logger.errors).containsExactly(failure);
    }
}
