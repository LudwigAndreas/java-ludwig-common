package ru.ludwigandreas.outbox.dispatch.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import ru.ludwigandreas.messaging.api.MessageHeaders;
import ru.ludwigandreas.outbox.dispatch.DispatchResult;
import ru.ludwigandreas.outbox.dispatch.OutboxDispatcher;
import ru.ludwigandreas.outbox.entity.OutboxMessage;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@code destination} is the Kafka topic name; the partition key is the ordering key, falling back to the aggregate id.
 *
 * <h2>The envelope, and why the header names are not literals here</h2>
 *
 * <p>Every name this class writes comes from {@link MessageHeaders} in
 * {@code messaging-spring-boot-starter}, which is the consumer half of the same contract. Before that
 * module existed the names lived here as string literals, and the consequence was visible across the
 * platform: one consumer copied a literal into a constant with a comment saying the two had to agree, and
 * two others gave up and parsed the payload instead - which is why {@code eventVersion} reached production
 * with nothing reading it. A shared constant makes a rename a compile error in the other half.
 *
 * <h2>Canonical names only</h2>
 *
 * <p>Until 3.0.0 each envelope header was also written under the pre-prefix name this dispatcher used
 * before {@link MessageHeaders} existed. Every consumer since 1.1.0 reads the canonical name, so 3.0.0
 * writes that one alone.
 */
public class KafkaOutboxDispatcher implements OutboxDispatcher {

    public static final String TRANSPORT = "KAFKA";

    private final String transport;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Duration sendTimeout;

    public KafkaOutboxDispatcher(KafkaTemplate<String, String> kafkaTemplate, Duration sendTimeout) {
        this(TRANSPORT, kafkaTemplate, sendTimeout);
    }

    /**
     * A dispatcher for one named cluster.
     *
     * @param transport     the transport name, {@code KAFKA:<cluster>}
     * @param kafkaTemplate a template producing to that cluster
     * @param sendTimeout   how long a send may take before it counts as failed
     */
    public KafkaOutboxDispatcher(String transport, KafkaTemplate<String, String> kafkaTemplate,
                                 Duration sendTimeout) {
        this.transport = transport;
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public String transport() {
        return transport;
    }

    @Override
    public DispatchResult dispatch(OutboxMessage message) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                message.getDestination(), partitionKey(message), message.getPayload());
        addHeader(record, MessageHeaders.EVENT_TYPE, message.getEventType());
        addHeader(record, MessageHeaders.EVENT_VERSION, String.valueOf(message.getEventVersion()));
        // Both new with this module. They were on OutboxEvent from the start and never reached the wire,
        // so a consumer could not tell which aggregate an event was about without parsing the payload.
        addHeader(record, MessageHeaders.AGGREGATE_TYPE, message.getAggregateType());
        addHeader(record, MessageHeaders.AGGREGATE_ID, message.getAggregateId());
        addHeader(record, MessageHeaders.IDEMPOTENCY_KEY, message.getIdempotencyKey());
        addHeader(record, MessageHeaders.TRACE_ID, message.getTraceId());
        // The producer's own clock, not the broker's. The two differ by however long the event sat in the
        // outbox table waiting for the poller, and that difference is the only way a consumer can tell
        // "this event is old because the producer was backed up" from "this event is old because I am
        // behind" - see MessageHeaders.PRODUCED_AT.
        addHeader(record, MessageHeaders.PRODUCED_AT, Instant.now().toString());

        // The correlation id is among the message's own headers: DefaultOutboxEventPublisher captured it when
        // the event was published, in the request's thread. Here, on the poller thread, there is none to read.
        Map<String, String> headers = message.getHeaders();
        if (headers != null) {
            headers.forEach((key, value) -> addHeader(record, key, value));
        }

        try {
            kafkaTemplate.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return DispatchResult.success();
        } catch (Exception e) {
            return DispatchResult.failure(e.getMessage(), true);
        }
    }

    private static void addHeader(ProducerRecord<String, String> record, String key, String value) {
        if (value != null) {
            record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String partitionKey(OutboxMessage message) {
        return message.getOrderingKey() != null ? message.getOrderingKey() : message.getAggregateId();
    }
}
