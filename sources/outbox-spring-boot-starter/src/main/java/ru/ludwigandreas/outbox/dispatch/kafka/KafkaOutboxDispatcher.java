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
 * <h2>Both spellings, for now</h2>
 *
 * <p>Each envelope header is written twice: under its canonical {@code ludwig-} name and under the
 * pre-prefix name this dispatcher used before. That is deliberate and temporary. A topic's retention
 * outlives a release, so at any moment during a rollout there are records on real topics carrying the old
 * spelling and consumers that have not been restarted yet - and a consumer reading {@code null} where a
 * value exists means, for {@code event-type}, a record silently ignored as an unknown type.
 *
 * <p>The legacy write can be dropped once every topic's retention window has passed with this version
 * deployed; {@link MessageHeaders} states the condition for dropping the legacy <em>read</em>, which is the
 * later of the two.
 */
public class KafkaOutboxDispatcher implements OutboxDispatcher {

    public static final String TRANSPORT = "KAFKA";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Duration sendTimeout;

    public KafkaOutboxDispatcher(KafkaTemplate<String, String> kafkaTemplate, Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public String transport() {
        return TRANSPORT;
    }

    @Override
    public DispatchResult dispatch(OutboxMessage message) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                message.getDestination(), partitionKey(message), message.getPayload());
        envelope(record, MessageHeaders.EVENT_TYPE, MessageHeaders.LEGACY_EVENT_TYPE,
                message.getEventType());
        envelope(record, MessageHeaders.EVENT_VERSION, MessageHeaders.LEGACY_EVENT_VERSION,
                String.valueOf(message.getEventVersion()));
        // Both new with this module. They were on OutboxEvent from the start and never reached the wire,
        // so a consumer could not tell which aggregate an event was about without parsing the payload.
        envelope(record, MessageHeaders.AGGREGATE_TYPE, null, message.getAggregateType());
        envelope(record, MessageHeaders.AGGREGATE_ID, null, message.getAggregateId());
        envelope(record, MessageHeaders.IDEMPOTENCY_KEY, MessageHeaders.LEGACY_IDEMPOTENCY_KEY,
                message.getIdempotencyKey());
        envelope(record, MessageHeaders.TRACE_ID, MessageHeaders.LEGACY_TRACE_ID, message.getTraceId());
        // The producer's own clock, not the broker's. The two differ by however long the event sat in the
        // outbox table waiting for the poller, and that difference is the only way a consumer can tell
        // "this event is old because the producer was backed up" from "this event is old because I am
        // behind" - see MessageHeaders.PRODUCED_AT.
        envelope(record, MessageHeaders.PRODUCED_AT, null, Instant.now().toString());

        // The correlation id is deliberately absent: observability-spring-boot-starter's producer
        // post-processor stamps X-Correlation-Id on every record produced through the Spring-managed
        // factory, and a second copy written here would be the one a consumer reads.
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

    /** One envelope header under its canonical name and, while the transition lasts, its legacy one. */
    private static void envelope(ProducerRecord<String, String> record, String canonical, String legacy,
                                 String value) {
        addHeader(record, canonical, value);
        if (legacy != null) {
            addHeader(record, legacy, value);
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
