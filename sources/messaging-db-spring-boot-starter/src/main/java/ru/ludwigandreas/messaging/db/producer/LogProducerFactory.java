package ru.ludwigandreas.messaging.db.producer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Serializer;
import org.springframework.kafka.core.ProducerFactory;
import ru.ludwigandreas.messaging.db.log.LogStore;

/**
 * A producer factory whose producers serialize a record as a Kafka producer would and append the bytes to
 * the log instead of sending them to a broker.
 *
 * <p>{@link LogClusterProducers} returns one of these for a replaced cluster, so everything the platform
 * produces there - the outbox's {@code KafkaOutboxDispatcher}, messaging's dead-letter recoverer - reaches
 * the log through its own unchanged code. Every header it puts on a record and every serializer decision
 * it makes, including a poison record's original bytes passing through as {@code byte[]}, therefore stays
 * in exactly one place.
 *
 * <p>{@link MockProducer} is extended only for the parts of the {@link Producer} contract a
 * {@code KafkaTemplate} touches besides {@code send} - flush, close, metrics - none of which mean anything
 * for an append that has committed by the time it returns.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public class LogProducerFactory<K, V> implements ProducerFactory<K, V> {

    private final LogStore store;
    private final Serializer<K> keySerializer;
    private final Serializer<V> valueSerializer;

    /**
     * Creates the factory.
     *
     * @param store           the log
     * @param keySerializer   the key serializer the caller asked for
     * @param valueSerializer the value serializer the caller asked for
     */
    public LogProducerFactory(LogStore store, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
        this.store = store;
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
    }

    @Override
    public Producer<K, V> createProducer() {
        return new LogProducer<>(store, keySerializer, valueSerializer);
    }

    @Override
    public Serializer<K> getKeySerializer() {
        return keySerializer;
    }

    @Override
    public Serializer<V> getValueSerializer() {
        return valueSerializer;
    }

    private static final class LogProducer<K, V> extends MockProducer<K, V> {

        private final LogStore store;
        private final Serializer<K> keySerializer;
        private final Serializer<V> valueSerializer;

        LogProducer(LogStore store, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
            this.store = store;
            this.keySerializer = keySerializer;
            this.valueSerializer = valueSerializer;
        }

        @Override
        public synchronized Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback) {
            byte[] key;
            byte[] value;
            LogStore.Appended appended;
            try {
                key = record.key() == null ? null
                        : keySerializer.serialize(record.topic(), record.headers(), record.key());
                value = record.value() == null ? null
                        : valueSerializer.serialize(record.topic(), record.headers(), record.value());
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
