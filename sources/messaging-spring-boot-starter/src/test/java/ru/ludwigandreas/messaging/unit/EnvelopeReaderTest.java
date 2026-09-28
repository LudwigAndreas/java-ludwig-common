package ru.ludwigandreas.messaging.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.messaging.api.EnvelopeReader;
import ru.ludwigandreas.messaging.api.InboundEnvelope;
import ru.ludwigandreas.messaging.api.MessageHeaders;

/** The consumer-side half of the envelope contract. */
class EnvelopeReaderTest {

    private final EnvelopeReader reader = new EnvelopeReader();

    @Test
    @DisplayName("reads every canonical envelope header off the record")
    void readsTheCanonicalEnvelope() {
        RecordHeaders headers = new RecordHeaders();
        put(headers, MessageHeaders.EVENT_TYPE, "OrderCreated");
        put(headers, MessageHeaders.EVENT_VERSION, "3");
        put(headers, MessageHeaders.AGGREGATE_TYPE, "Order");
        put(headers, MessageHeaders.AGGREGATE_ID, "4711");
        put(headers, MessageHeaders.IDEMPOTENCY_KEY, "created-4711");
        put(headers, MessageHeaders.CORRELATION_ID, "corr-1");
        put(headers, MessageHeaders.TRACE_ID, "trace-1");
        put(headers, MessageHeaders.PRODUCED_AT, "2026-09-26T10:00:00Z");

        InboundEnvelope<String> envelope = reader.read(record("orders", headers, "{}"));

        assertThat(envelope.eventType()).isEqualTo("OrderCreated");
        assertThat(envelope.eventVersion()).isEqualTo(3);
        assertThat(envelope.aggregateType()).isEqualTo("Order");
        assertThat(envelope.aggregateId()).isEqualTo("4711");
        assertThat(envelope.idempotencyKey()).isEqualTo("created-4711");
        assertThat(envelope.correlationId()).isEqualTo("corr-1");
        assertThat(envelope.traceId()).isEqualTo("trace-1");
        assertThat(envelope.producedAt()).isEqualTo(Instant.parse("2026-09-26T10:00:00Z"));
        assertThat(envelope.coordinates()).isEqualTo("orders-2@17");
    }

    /**
     * The transition case. A topic's retention outlives a release, so a consumer will be asked to read
     * records produced before the canonical names existed - and reading null for the event type means a
     * record silently ignored as an unknown type, which is not an error anywhere.
     */
    @Test
    @DisplayName("falls back to the pre-prefix header names")
    void fallsBackToTheLegacyNames() {
        RecordHeaders headers = new RecordHeaders();
        put(headers, MessageHeaders.LEGACY_EVENT_TYPE, "OrderCreated");
        put(headers, MessageHeaders.LEGACY_EVENT_VERSION, "2");
        put(headers, MessageHeaders.LEGACY_IDEMPOTENCY_KEY, "created-4711");
        put(headers, MessageHeaders.LEGACY_TRACE_ID, "trace-1");

        InboundEnvelope<String> envelope = reader.read(record("orders", headers, "{}"));

        assertThat(envelope.eventType()).isEqualTo("OrderCreated");
        assertThat(envelope.eventVersion()).isEqualTo(2);
        assertThat(envelope.idempotencyKey()).isEqualTo("created-4711");
        assertThat(envelope.traceId()).isEqualTo("trace-1");
    }

    @Test
    @DisplayName("prefers the canonical name when both are present, as during a rollout")
    void prefersTheCanonicalName() {
        RecordHeaders headers = new RecordHeaders();
        put(headers, MessageHeaders.LEGACY_EVENT_TYPE, "old");
        put(headers, MessageHeaders.EVENT_TYPE, "new");

        assertThat(reader.read(record("orders", headers, "{}")).eventType()).isEqualTo("new");
    }

    /**
     * A record produced before the version header existed is version 1, not version zero and not a
     * failure: that is the default {@code OutboxEvent} applies on the producing side, so both halves
     * agree about what a missing header means.
     */
    @Test
    @DisplayName("a missing version header reads as version 1")
    void defaultsTheVersion() {
        assertThat(reader.read(record("orders", new RecordHeaders(), "{}")).eventVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unparseable version or timestamp does not fail the read")
    void toleratesMalformedHeaders() {
        RecordHeaders headers = new RecordHeaders();
        put(headers, MessageHeaders.EVENT_VERSION, "not-a-number");
        put(headers, MessageHeaders.PRODUCED_AT, "yesterday");

        InboundEnvelope<String> envelope = reader.read(record("orders", headers, "{}"));

        assertThat(envelope.eventVersion()).isEqualTo(1);
        assertThat(envelope.producedAt()).isNull();
    }

    /**
     * The correlation header's name belongs to the observability module and is configurable there, so a
     * reader that hard-coded it would read nothing in exactly the deployment that had changed it.
     */
    @Test
    @DisplayName("reads the correlation id under the configured header name")
    void readsAConfiguredCorrelationHeader() {
        RecordHeaders headers = new RecordHeaders();
        put(headers, "X-Request-Id", "corr-9");

        assertThat(new EnvelopeReader("X-Request-Id").read(record("orders", headers, "{}")).correlationId())
                .isEqualTo("corr-9");
    }

    @Test
    @DisplayName("a null value is a tombstone and every header is exposed as text")
    void exposesTombstonesAndAllHeaders() {
        RecordHeaders headers = new RecordHeaders();
        put(headers, "x-partner-hint", "batch-3");
        headers.add("x-removed", null);

        InboundEnvelope<String> envelope = reader.read(record("orders", headers, null));

        assertThat(envelope.tombstone()).isTrue();
        assertThat(envelope.headers()).containsEntry("x-partner-hint", "batch-3")
                .doesNotContainKey("x-removed");
    }

    private static void put(RecordHeaders headers, String name, String value) {
        headers.add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static ConsumerRecord<String, String> record(String topic, RecordHeaders headers, String value) {
        return new ConsumerRecord<>(topic, 2, 17L, 1_700_000_000_000L,
                org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0, "key", value, headers,
                java.util.Optional.empty());
    }
}
