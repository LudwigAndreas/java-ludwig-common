package ru.ludwigandreas.hotreload.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.redaction.KeyNameSensitivityClassifier;
import ru.ludwigandreas.audit.redaction.ProvenanceSensitivityClassifier;
import ru.ludwigandreas.audit.redaction.Redaction;
import ru.ludwigandreas.audit.redaction.Redactor;
import ru.ludwigandreas.audit.redaction.SensitivityClassifier;
import ru.ludwigandreas.hotreload.core.SourceChangeEvent;

class AuditingSourceChangeListenerTest {

    private static class RecordingSink implements AuditSink {
        final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }
    }

    /** The same two rules this module always applied, now composed rather than hard-coded. */
    private static Redactor redactor() {
        return new Redactor(SensitivityClassifier.anyOf(
                new KeyNameSensitivityClassifier(), new ProvenanceSensitivityClassifier()));
    }

    private static AuditingSourceChangeListener listener(RecordingSink sink) {
        return new AuditingSourceChangeListener(sink, redactor(), "instance-1");
    }

    @Test
    void recordsOldAndNewValuesForEveryChangedKey() {
        RecordingSink sink = new RecordingSink();

        listener(sink).onChange(new SourceChangeEvent("file:/etc/app.properties",
                Map.of("app.name", "v2"), Map.of("app.name", "v1"), Set.of("app.name")));

        assertThat(sink.events).hasSize(1);
        AuditEvent event = sink.events.get(0);
        assertThat(event.category()).isEqualTo("config");
        assertThat(event.action()).isEqualTo(HotReloadAuditEntry.ACTION_APPLIED);
        assertThat(event.resource().type()).isEqualTo("file:/etc/app.properties");
        assertThat(event.actor().subject()).isEqualTo("instance-1");
        assertThat(event.actor().principalType()).isEqualTo(HotReloadAuditEntry.PRINCIPAL_TYPE);
        assertThat(changeOf(event, "app.name")).containsEntry("old", "v1").containsEntry("new", "v2");
    }

    @Test
    void redactsSensitiveValuesBeforeTheyReachTheSink() {
        RecordingSink sink = new RecordingSink();

        listener(sink).onChange(new SourceChangeEvent("vault:secret/myapp",
                Map.of("db.password", "newSecret"), Map.of("db.password", "oldSecret"),
                Set.of("db.password")));

        assertThat(changeOf(sink.events.get(0), "db.password"))
                .containsEntry("old", Redaction.MASK)
                .containsEntry("new", Redaction.MASK);
    }

    /** Provenance alone, with a key name no heuristic would flag - the rule a name regex cannot express. */
    @Test
    void redactsAnInnocuouslyNamedVaultKey() {
        RecordingSink sink = new RecordingSink();

        listener(sink).onChange(new SourceChangeEvent("vault:secret/myapp",
                Map.of("username", "bob"), Map.of("username", "alice"), Set.of("username")));

        assertThat(changeOf(sink.events.get(0), "username")).containsEntry("new", Redaction.MASK);
    }

    @Test
    void doesNothingWhenThereAreNoChangedKeys() {
        RecordingSink sink = new RecordingSink();

        listener(sink).onChange(new SourceChangeEvent("file:/etc/app.properties", Map.of(), Map.of(), Set.of()));

        assertThat(sink.events).isEmpty();
    }

    @Test
    void recordsAFailedReloadAsAFailureRatherThanAnEmptyChangeSet() {
        RecordingSink sink = new RecordingSink();

        listener(sink).onError("file:/etc/app.properties", new IllegalStateException("boom"));

        assertThat(sink.events).hasSize(1);
        AuditEvent event = sink.events.get(0);
        assertThat(event.action()).isEqualTo(HotReloadAuditEntry.ACTION_FAILED);
        assertThat(event.outcome().status()).isEqualTo(AuditOutcome.Status.FAILURE);
        assertThat(event.outcome().reason()).contains("boom");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> changeOf(AuditEvent event, String key) {
        Map<String, Object> changes = (Map<String, Object>) event.attributes().get("changes");
        return (Map<String, Object>) changes.get(key);
    }
}
