package ru.ludwigandreas.outbox.unit;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.audit.NoopAuditSink;
import ru.ludwigandreas.outbox.audit.AuditSinkOutboxAuditLogger;
import ru.ludwigandreas.job.core.backoff.BackoffCalculator;
import ru.ludwigandreas.outbox.config.OutboxProperties;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.metrics.NoopOutboxMetrics;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;
import ru.ludwigandreas.outbox.scheduler.OutboxOutcomeRecorder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxOutcomeRecorderTest {

    private static class RecordingMetrics extends NoopOutboxMetrics {
        final List<Duration> latencies = new ArrayList<>();

        @Override
        public void recordEndToEndLatency(String eventType, String transport, Duration duration) {
            latencies.add(duration);
        }
    }

    @Test
    void recordSuccessRecordsEndToEndLatencyFromCreatedAtToNow() {
        UUID id = UUID.randomUUID();
        OutboxMessage message = new OutboxMessage();
        message.setId(id);
        message.setEventType("OrderCreated");
        message.setTransport("KAFKA");
        message.setDestination("orders-topic");
        message.setCreatedAt(Instant.now().minus(Duration.ofSeconds(5)));

        OutboxMessageRepository repository = mock(OutboxMessageRepository.class);
        when(repository.getByIdOrThrow(id)).thenReturn(message);

        RecordingMetrics metrics = new RecordingMetrics();
        OutboxOutcomeRecorder recorder = new OutboxOutcomeRecorder(
                repository,
                new BackoffCalculator(new OutboxProperties.Retry().toBackoffPolicy()),
                new AuditSinkOutboxAuditLogger(NoopAuditSink.INSTANCE),
                metrics,
                new OutboxProperties());

        recorder.recordSuccess(id);

        assertThat(metrics.latencies).hasSize(1);
        assertThat(metrics.latencies.get(0)).isGreaterThanOrEqualTo(Duration.ofSeconds(5));
    }
}
