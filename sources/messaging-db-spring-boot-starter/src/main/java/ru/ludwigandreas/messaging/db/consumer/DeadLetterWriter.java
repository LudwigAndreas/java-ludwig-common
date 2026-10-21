package ru.ludwigandreas.messaging.db.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.SerializationUtils;
import ru.ludwigandreas.messaging.api.DeadLetterTopics;
import ru.ludwigandreas.messaging.db.log.LogStore;
import ru.ludwigandreas.messaging.db.log.StoredRecord;

/**
 * Writes a dead-letter record: to the log while the transport is active, to Kafka while it drains.
 *
 * <p>The record keeps the source record's key, payload and headers, plus the original coordinates and the
 * exception's type under spring-kafka's own {@code kafka_dlt-*} names, so a dead-letter consumer reads the
 * same headers whichever transport wrote it. The exception's message and stack trace are deliberately not
 * written, unlike spring-kafka's default: on this platform they routinely quote the payload.
 */
public class DeadLetterWriter {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

    private final LogStore store;
    private final KafkaTemplate<Object, Object> kafka;
    private final ObjectMapper objectMapper;

    /**
     * Creates the writer.
     *
     * @param store        the log, written while the transport is active
     * @param kafka        the dead-letter producer while draining, {@code null} while active
     * @param objectMapper serializes a deserialized payload that has to be written back out
     */
    public DeadLetterWriter(LogStore store, KafkaTemplate<Object, Object> kafka, ObjectMapper objectMapper) {
        this.store = store;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    /**
     * Dead-letters a record read from the log, with the bytes it was stored as.
     *
     * @param record    the stored record
     * @param exception why it is being dead-lettered
     * @param suffix    the consumer's dead-letter suffix
     */
    public void write(StoredRecord record, Exception exception, String suffix) {
        write(record.topic(), record.partition(), record.offset(), record.key(), record.payload(),
                record.headers(), exception, suffix);
    }

    /**
     * Dead-letters a record read from Kafka. A payload that failed to deserialize is written as the bytes
     * the exception carries, which are the only faithful copy of it; any other payload is serialized back.
     *
     * @param topic     the source topic
     * @param partition the source partition
     * @param offset    the source offset
     * @param key       the deserialized key
     * @param value     the deserialized value
     * @param headers   the source headers
     * @param exception why it is being dead-lettered
     * @param suffix    the consumer's dead-letter suffix
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - the coordinates of a consumer record, passed apart so that one
    // path can hand over stored bytes and the other deserialized values.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public void write(String topic, int partition, long offset, Object key, Object value, Headers headers,
                      Exception exception, String suffix) {
        byte[] payload = rawPayload(exception);
        write(topic, partition, offset, bytes(key), payload != null ? payload : bytes(value), headers, exception,
                suffix);
    }

    // SUPPRESS CHECKSTYLE ParameterNumber - see above.
    @SuppressWarnings("checkstyle:ParameterNumber")
    private void write(String topic, int partition, long offset, byte[] key, byte[] payload, Headers source,
                       Exception exception, String suffix) {
        String deadLetterTopic = DeadLetterTopics.forTopic(topic, suffix);
        Headers headers = new RecordHeaders();
        for (Header header : source) {
            if (!header.key().startsWith(SerializationUtils.DESERIALIZER_EXCEPTION_HEADER_PREFIX)) {
                headers.add(header);
            }
        }
        headers.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, topic.getBytes(StandardCharsets.UTF_8));
        headers.add(KafkaHeaders.DLT_ORIGINAL_PARTITION, ByteBuffer.allocate(Integer.BYTES).putInt(partition).array());
        headers.add(KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(Long.BYTES).putLong(offset).array());
        headers.add(KafkaHeaders.DLT_EXCEPTION_FQCN, exception.getClass().getName().getBytes(StandardCharsets.UTF_8));
        if (kafka == null) {
            store.append(deadLetterTopic, key, headers, payload);
            return;
        }
        ProducerRecord<Object, Object> record = new ProducerRecord<>(deadLetterTopic, null, key, payload, headers);
        try {
            kafka.send(record).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while dead-lettering to " + deadLetterTopic, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Dead-letter send to " + deadLetterTopic + " failed", e);
        }
    }

    private static byte[] rawPayload(Throwable exception) {
        for (Throwable cause = exception; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            if (cause instanceof DeserializationException deserialization && !deserialization.isKey()) {
                return deserialization.getData();
            }
        }
        return null;
    }

    private byte[] bytes(Object value) {
        if (value == null || value instanceof byte[]) {
            return (byte[]) value;
        }
        if (value instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A dead-letter payload of type " + value.getClass().getName()
                    + " could not be serialized", e);
        }
    }
}
