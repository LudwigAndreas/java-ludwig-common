package ru.ludwigandreas.messaging.db.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.SerializationUtils;
import ru.ludwigandreas.messaging.api.DeadLetterTopics;
import ru.ludwigandreas.messaging.cluster.KafkaClusterProducers;
import ru.ludwigandreas.messaging.db.log.StoredRecord;
import ru.ludwigandreas.messaging.dlt.DeadLetterTemplates;

/**
 * Dead-letters a record read from the log, to the consumer's dead-letter cluster.
 *
 * <p>Through messaging's producer seam, as every other dead letter is: a replaced cluster's dead letters
 * are appended to the log while active and go to its own address while draining, and any other cluster's
 * go to that cluster. The record keeps the stored key, payload bytes and headers, plus the original
 * coordinates and the exception's type under spring-kafka's own {@code kafka_dlt-*} names, so a dead-letter
 * consumer reads the same headers whichever transport wrote it. The exception's message and stack trace are
 * deliberately not written, unlike spring-kafka's default: on this platform they routinely quote the
 * payload.
 */
public class DeadLetterWriter {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

    private final KafkaClusterProducers producers;
    private final ObjectMapper objectMapper;
    private final Map<String, KafkaTemplate<Object, Object>> templates = new ConcurrentHashMap<>();

    /**
     * Creates the writer.
     *
     * @param producers    the producer seam, the replaced clusters behind the log
     * @param objectMapper the application's mapper, for messaging's dead-letter serializer
     */
    public DeadLetterWriter(KafkaClusterProducers producers, ObjectMapper objectMapper) {
        this.producers = producers;
        this.objectMapper = objectMapper;
    }

    /**
     * Dead-letters a record read from the log, with the bytes it was stored as.
     *
     * @param record    the stored record
     * @param exception why it is being dead-lettered
     * @param cluster   the consumer's dead-letter cluster
     * @param suffix    the consumer's dead-letter suffix
     */
    public void write(StoredRecord record, Exception exception, String cluster, String suffix) {
        String deadLetterTopic = DeadLetterTopics.forTopic(record.topic(), suffix);
        Headers headers = new RecordHeaders();
        for (Header header : record.headers()) {
            if (!header.key().startsWith(SerializationUtils.DESERIALIZER_EXCEPTION_HEADER_PREFIX)) {
                headers.add(header);
            }
        }
        headers.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, record.topic().getBytes(StandardCharsets.UTF_8));
        headers.add(KafkaHeaders.DLT_ORIGINAL_PARTITION,
                ByteBuffer.allocate(Integer.BYTES).putInt(record.partition()).array());
        headers.add(KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(Long.BYTES).putLong(record.offset()).array());
        headers.add(KafkaHeaders.DLT_EXCEPTION_FQCN, exception.getClass().getName().getBytes(StandardCharsets.UTF_8));
        ProducerRecord<Object, Object> deadLetter =
                new ProducerRecord<>(deadLetterTopic, null, record.key(), record.payload(), headers);
        KafkaTemplate<Object, Object> template = templates.computeIfAbsent(cluster,
                name -> DeadLetterTemplates.create(producers, name, objectMapper));
        try {
            template.send(deadLetter).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while dead-lettering to " + deadLetterTopic, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Dead-letter send to " + deadLetterTopic + " on cluster '" + cluster
                    + "' failed", e);
        }
    }
}
