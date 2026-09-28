package ru.ludwigandreas.messaging.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.messaging.api.EnvelopeReader;
import ru.ludwigandreas.messaging.audit.DeadLetterAudit;
import ru.ludwigandreas.messaging.dlt.AuditingDeadLetterRecoverer;
import ru.ludwigandreas.messaging.metrics.NoopMessagingMetrics;

/**
 * A dead-lettered record is counted and audited, and a dead-lettering that did not happen is counted and
 * audited differently.
 *
 * <p>The second is the one worth having. {@code ludwig.messaging.dropped} should be zero at all times once
 * a dead-letter topic exists, so a non-zero value does not mean "some records failed" - it means the
 * recoverer itself is failing, which is the case where the platform believes it has a safety net and does
 * not.
 */
class AuditingDeadLetterRecovererTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC);

    private final RecordingMetrics metrics = new RecordingMetrics();
    private final List<AuditEvent> audited = new ArrayList<>();
    private final AuditSink sink = audited::add;

    @Test
    @DisplayName("a successful send is counted against the derived dead-letter topic and audited as a failure")
    void countsAndAuditsASuccessfulSend() {
        AuditingDeadLetterRecoverer recoverer = recoverer((record, exception) -> { }, 4);

        recoverer.accept(record(), new ListenerExecutionFailedException("wrapper", new IllegalStateException()));

        assertThat(metrics.deadLettered).containsExactly("orders -> orders.dlt (IllegalStateException)");
        assertThat(metrics.dropped).isEmpty();
        AuditEvent event = audited.get(0);
        assertThat(event.action()).isEqualTo(DeadLetterAudit.ACTION_DEAD_LETTERED);
        assertThat(event.outcome().status()).isEqualTo(AuditOutcome.Status.FAILURE);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-09-26T12:00:00Z"));
        assertThat(event.attributes()).containsEntry("topic", "orders")
                .containsEntry("deadLetterTopic", "orders.dlt")
                .containsEntry("attempts", 4);
    }

    /**
     * The payload never reaches the trail: this platform's topics carry a person's settings and directory
     * data, and the trail is retained longer than the topic and read by more people.
     */
    @Test
    @DisplayName("the audit event carries the envelope but never the payload")
    void neverAuditsThePayload() {
        recoverer((record, exception) -> { }, 4).accept(record(), new IllegalStateException("boom"));

        assertThat(audited.get(0).attributes().values()).doesNotContain("a person's settings");
        assertThat(audited.get(0).attributes()).doesNotContainKey("payload");
    }

    /**
     * A non-retryable failure reaches the recoverer on its first delivery, which is the point of
     * registering it non-retryable - so reporting the full budget would overstate what was spent.
     */
    @Test
    @DisplayName("a non-retryable failure is audited as one attempt, not four")
    void reportsOneAttemptForANonRetryableFailure() {
        AuditingDeadLetterRecoverer recoverer = recoverer((record, exception) -> { }, 4);

        recoverer.accept(record(), new ListenerExecutionFailedException("wrapper",
                new DeserializationException("bad", new byte[0], false, new RuntimeException())));

        assertThat(audited.get(0).attributes()).containsEntry("attempts", 1);
    }

    @Test
    @DisplayName("a failed send is counted as dropped, audited as dropped, and rethrown")
    void reportsAFailedSend() {
        ConsumerRecordRecoverer failing = (record, exception) -> {
            throw new IllegalStateException("the dead-letter topic does not exist");
        };
        AuditingDeadLetterRecoverer recoverer = recoverer(failing, 4);

        assertThatThrownBy(() -> recoverer.accept(record(), new IllegalStateException("boom")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist");

        assertThat(metrics.dropped).containsExactly("orders");
        assertThat(metrics.deadLettered).isEmpty();
        AuditEvent event = audited.get(0);
        assertThat(event.action()).isEqualTo(DeadLetterAudit.ACTION_DROPPED);
        // Absent rather than present-and-null: AuditEvent drops a null attribute, because "absent" and
        // "present and null" mean the same thing to every reader of the trail. The action is what says the
        // record never reached a dead-letter topic.
        assertThat(event.attributes()).doesNotContainKey("deadLetterTopic");
        assertThat(event.outcome().reason()).contains("recovery to orders.dlt failed");
    }

    @Test
    @DisplayName("a consumer with a configured suffix dead-letters to that topic")
    void honoursAConfiguredSuffix() {
        recoverer((record, exception) -> { }, 4, "-failed").accept(record(), new IllegalStateException());

        assertThat(metrics.deadLettered).containsExactly("orders -> orders-failed (IllegalStateException)");
    }

    private AuditingDeadLetterRecoverer recoverer(ConsumerRecordRecoverer delegate, int attempts) {
        return recoverer(delegate, attempts, ".dlt");
    }

    private AuditingDeadLetterRecoverer recoverer(ConsumerRecordRecoverer delegate, int attempts,
                                                  String suffix) {
        return new AuditingDeadLetterRecoverer(delegate, new EnvelopeReader(), metrics, sink, CLOCK,
                suffix, attempts);
    }

    private static ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>("orders", 1, 42L, "key", "a person's settings");
    }

    /** Records what was counted, spelled so an assertion reads as the sentence the metric means. */
    private static final class RecordingMetrics extends NoopMessagingMetrics {

        private final List<String> deadLettered = new ArrayList<>();
        private final List<String> dropped = new ArrayList<>();

        @Override
        public void recordDeadLettered(String topic, String deadLetterTopic, String exception) {
            deadLettered.add(topic + " -> " + deadLetterTopic + " (" + exception + ")");
        }

        @Override
        public void recordDropped(String topic) {
            dropped.add(topic);
        }
    }
}
