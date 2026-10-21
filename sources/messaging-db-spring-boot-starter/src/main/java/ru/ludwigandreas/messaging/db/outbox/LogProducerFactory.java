package ru.ludwigandreas.messaging.db.outbox;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.ProducerFactory;
import ru.ludwigandreas.messaging.db.log.LogStore;

/**
 * A producer factory whose producers append to the log instead of sending to a broker.
 *
 * <p>This is how the outbox writes to the log without this module re-implementing its dispatcher: the
 * outbox's own {@code KafkaOutboxDispatcher} is constructed over a {@code KafkaTemplate} built on this
 * factory, so every header it puts on a record - the envelope's names, the produced-at instant, the
 * message's own headers - reaches the log exactly as it would reach a topic. A second header-building
 * implementation here would be one more place for the two transports to drift apart.
 *
 * <p>{@link MockProducer} is extended only for the parts of the {@link Producer} contract a
 * {@code KafkaTemplate} touches besides {@code send} - flush, close, metrics - none of which mean anything
 * for an append that has committed by the time it returns.
 */
public class LogProducerFactory implements ProducerFactory<String, String> {

    private final LogStore store;

    /**
     * Creates the factory.
     *
     * @param store the log
     */
    public LogProducerFactory(LogStore store) {
        this.store = store;
    }

    @Override
    public Producer<String, String> createProducer() {
        return new LogProducer(store);
    }

    private static final class LogProducer extends MockProducer<String, String> {

        private final LogStore store;

        LogProducer(LogStore store) {
            this.store = store;
        }

        @Override
        public synchronized Future<RecordMetadata> send(ProducerRecord<String, String> record, Callback callback) {
            byte[] key = record.key() == null ? null : record.key().getBytes(StandardCharsets.UTF_8);
            byte[] value = record.value() == null ? null : record.value().getBytes(StandardCharsets.UTF_8);
            LogStore.Appended appended;
            try {
                appended = store.append(record.topic(), key, record.headers(), value);
            // CHECKSTYLE.OFF: IllegalCatch - the producer contract reports a failed send through the callback
            // and the future, never by throwing; KafkaOutboxDispatcher turns that into a retryable failure.
            } catch (RuntimeException e) {
            // CHECKSTYLE.ON: IllegalCatch
                if (callback != null) {
                    callback.onCompletion(null, e);
                }
                return CompletableFuture.failedFuture(e);
            }
            RecordMetadata metadata = new RecordMetadata(new TopicPartition(appended.topic(), appended.partition()),
                    appended.offset(), 0, appended.producedAt().toEpochMilli(),
                    key == null ? -1 : key.length, value == null ? -1 : value.length);
            if (callback != null) {
                callback.onCompletion(metadata, null);
            }
            return CompletableFuture.completedFuture(metadata);
        }
    }
}
