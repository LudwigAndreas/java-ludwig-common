package ru.ludwigandreas.messaging.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

/**
 * Turns a {@link ConsumerRecord} into an {@link InboundEnvelope}.
 *
 * <p>One class rather than a static helper, because the correlation header's name is a deployment's
 * choice - {@code observability-spring-boot-starter} owns it and lets it be configured - and a reader
 * that hard-coded it would read {@code null} in exactly the deployment that had changed it.
 *
 * <h2>Nothing here throws on a malformed header</h2>
 *
 * <p>An unparseable {@code ludwig-event-version} or {@code ludwig-produced-at} reads as the default,
 * not as a failure. The reason is where this runs: it is called from inside a listener, on a record the
 * container has already accepted, and the only thing throwing here would achieve is turning a
 * cosmetically broken header into a retried and eventually dead-lettered record. The version check that
 * <em>does</em> refuse a record is deliberately upstream of this class, in
 * {@code VersionGatingDeserializer}, where it can refuse before the payload is deserialized at all.
 *
 * <p>Header values are decoded as UTF-8 and never logged by this class. A header is producer-supplied
 * text and this platform's topics carry directory data and a person's settings; what a reader needs in
 * a log line is the record's coordinates, which {@link InboundEnvelope#coordinates()} gives.
 */
public class EnvelopeReader {

    private final String correlationHeaderName;

    /**
     * Creates a reader using the platform's default correlation header name.
     */
    public EnvelopeReader() {
        this(MessageHeaders.CORRELATION_ID);
    }

    /**
     * Creates a reader.
     *
     * @param correlationHeaderName the correlation header's name, as configured for this deployment;
     *                              blank or {@code null} falls back to the platform default rather
     *                              than reading no correlation id at all
     */
    public EnvelopeReader(String correlationHeaderName) {
        this.correlationHeaderName = correlationHeaderName == null || correlationHeaderName.isBlank()
                ? MessageHeaders.CORRELATION_ID
                : correlationHeaderName;
    }

    /**
     * Reads the envelope off a record.
     *
     * @param record the record, whose value has already been deserialized by the container
     * @param <K>    the key type, which the envelope does not carry: a message key is a partitioning
     *               decision, not a fact about the event, and a consumer that needs it has the record
     * @param <V>    the payload type
     * @return the envelope
     */
    public <K, V> InboundEnvelope<V> read(ConsumerRecord<K, V> record) {
        Headers headers = record.headers();
        return new InboundEnvelope<>(
                record.value(),
                first(headers, MessageHeaders.EVENT_TYPE, MessageHeaders.LEGACY_EVENT_TYPE),
                version(headers),
                first(headers, MessageHeaders.AGGREGATE_TYPE, null),
                first(headers, MessageHeaders.AGGREGATE_ID, null),
                first(headers, MessageHeaders.IDEMPOTENCY_KEY, MessageHeaders.LEGACY_IDEMPOTENCY_KEY),
                first(headers, correlationHeaderName, null),
                first(headers, MessageHeaders.TRACE_ID, MessageHeaders.LEGACY_TRACE_ID),
                producedAt(headers),
                record.topic(),
                record.partition(),
                record.offset(),
                Instant.ofEpochMilli(record.timestamp()),
                allAsText(headers));
    }

    /**
     * The event version, or {@link InboundEnvelope#DEFAULT_EVENT_VERSION}.
     *
     * <p>A record with no version header is version 1 rather than version zero or an error: that is the
     * default {@code OutboxEvent} applies on the producing side, so the two halves agree about what a
     * missing header means. Reading it as anything else would make every record produced before the
     * header existed look like an unknown version.
     */
    private int version(Headers headers) {
        String value = first(headers, MessageHeaders.EVENT_VERSION, MessageHeaders.LEGACY_EVENT_VERSION);
        if (value == null) {
            return InboundEnvelope.DEFAULT_EVENT_VERSION;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return InboundEnvelope.DEFAULT_EVENT_VERSION;
        }
    }

    private Instant producedAt(Headers headers) {
        String value = first(headers, MessageHeaders.PRODUCED_AT, null);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * The canonical header's value, falling back to the legacy spelling.
     *
     * <p>{@code lastHeader} rather than the first, matching what Kafka itself does when a key appears
     * twice: the last write wins, and an interceptor that re-stamps a header expects to be the one read.
     */
    private String first(Headers headers, String canonical, String legacy) {
        String value = text(headers.lastHeader(canonical));
        if (value != null || legacy == null) {
            return value;
        }
        return text(headers.lastHeader(legacy));
    }

    private static String text(Header header) {
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * Every header as text, last write winning, in iteration order.
     *
     * <p>Ordered rather than a {@code Map.of}, because the order a producer added its headers is the
     * order an operator reading a dead-letter record's dump expects to see them.
     */
    private static Map<String, String> allAsText(Headers headers) {
        Map<String, String> all = new LinkedHashMap<>();
        for (Header header : headers) {
            all.put(header.key(), text(header));
        }
        // Map.copyOf, which the record's constructor applies, rejects a null value - and a header with a
        // null value is legal on the wire and is how a producer removes one.
        all.entrySet().removeIf(entry -> entry.getValue() == null);
        return all;
    }
}
