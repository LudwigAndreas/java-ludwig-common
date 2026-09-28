package ru.ludwigandreas.messaging.api;

import java.time.Instant;
import java.util.Map;

/**
 * One consumed record, with the producer's envelope read back off it.
 *
 * <p>The mirror of {@code OutboxEvent}. The producer side has always had a typed object naming every
 * field it puts on the wire; the consumer side had a {@code String} payload and, if it was lucky, one
 * {@code @Header} parameter. Everything the producer bothered to send - the aggregate it was about,
 * the version of the payload's schema, the key that identifies the event across republishes - was on
 * the record and unreachable without knowing the spelling.
 *
 * <p>Deliberately not the same class as {@code OutboxEvent}. They are not the same thing: the producer
 * names a route and an ordering key, which are instructions to the outbox and mean nothing here, and
 * the consumer knows a topic, a partition and an offset, which do not exist yet when the producer
 * builds its event. Sharing one class would mean half the fields being null in either direction, and a
 * consumer could not tell a field the producer omitted from a field that only applies to the other
 * half.
 *
 * @param payload       the deserialized payload; {@code null} for a tombstone
 * @param eventType     {@link MessageHeaders#EVENT_TYPE}, or {@code null} when the producer sent none
 * @param eventVersion  {@link MessageHeaders#EVENT_VERSION}, defaulting to {@code 1} when absent -
 *                      the same default {@code OutboxEvent} applies on the producing side, so a
 *                      record from a producer that predates the header reads as version 1 rather than
 *                      as an unknown version that would be dead-lettered
 * @param aggregateType {@link MessageHeaders#AGGREGATE_TYPE}, or {@code null}
 * @param aggregateId   {@link MessageHeaders#AGGREGATE_ID}, or {@code null}
 * @param idempotencyKey {@link MessageHeaders#IDEMPOTENCY_KEY}, or {@code null}
 * @param correlationId {@link MessageHeaders#CORRELATION_ID}, or {@code null}
 * @param traceId       {@link MessageHeaders#TRACE_ID}, or {@code null}
 * @param producedAt    {@link MessageHeaders#PRODUCED_AT}, or {@code null} when the producer sent
 *                      none or sent something unparseable - never the broker's timestamp, which is a
 *                      different fact and is available on {@code timestamp}
 * @param topic         the topic the record came from
 * @param partition     the partition
 * @param offset        the offset
 * @param timestamp     the broker's record timestamp
 * @param headers       every header on the record as UTF-8 text, envelope headers included, so a
 *                      consumer can read one this record type did not anticipate without reaching for
 *                      the {@code ConsumerRecord}
 */
public record InboundEnvelope<T>(
        T payload,
        String eventType,
        int eventVersion,
        String aggregateType,
        String aggregateId,
        String idempotencyKey,
        String correlationId,
        String traceId,
        Instant producedAt,
        String topic,
        int partition,
        long offset,
        Instant timestamp,
        Map<String, String> headers) {

    /** The default a record with no {@link MessageHeaders#EVENT_VERSION} header is read as. */
    public static final int DEFAULT_EVENT_VERSION = 1;

    /**
     * Normalises the envelope.
     *
     * <p>{@code headers} is copied and made unmodifiable for the reason every record in this platform
     * does it: a caller-mutable map on an object that is passed to a listener, a metric and an audit
     * event is a value that can change between the three reading it.
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor. The envelope is wide
    // because the wire contract is wide, and every component is named at the call site by EnvelopeReader.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public InboundEnvelope {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("An InboundEnvelope needs the topic it was read from");
        }
        eventVersion = eventVersion <= 0 ? DEFAULT_EVENT_VERSION : eventVersion;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /**
     * Whether this is a tombstone - a record with a key and no value.
     *
     * @return {@code true} when the payload is absent
     */
    public boolean tombstone() {
        return payload == null;
    }

    /**
     * Where this record sits, in the {@code topic-partition@offset} form every log line and metric in
     * this platform uses for a record.
     *
     * @return the coordinates
     */
    public String coordinates() {
        return topic + "-" + partition + "@" + offset;
    }
}
