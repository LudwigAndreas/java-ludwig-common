package ru.ludwigandreas.outbox.dispatch.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import ru.ludwigandreas.outbox.dispatch.DispatchResult;
import ru.ludwigandreas.outbox.dispatch.OutboxDispatcher;
import ru.ludwigandreas.outbox.entity.OutboxMessage;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** {@code destination} is the Kafka topic name; the partition key is the ordering key, falling back to the aggregate id. */
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
        addHeader(record, "event-type", message.getEventType());
        addHeader(record, "event-version", String.valueOf(message.getEventVersion()));
        addHeader(record, "idempotency-key", message.getIdempotencyKey());
        addHeader(record, "trace-id", message.getTraceId());
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
