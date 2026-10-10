package ru.ludwigandreas.testsupport.local.fixture;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.kafka.core.KafkaOperations;

/**
 * Publishes seed messages, and delivers one message more than once.
 *
 * <p>A consumer is at-least-once by construction, so "the same record arrives twice" is not an edge
 * case but the contract, and it is the case nobody can produce on demand against a healthy broker.
 * {@link #deliver(KafkaOperations, ProducerRecord, int)} produces it: the copies share key, headers
 * and payload and differ only in offset, which is exactly what a redelivery after a lost commit looks
 * like to the listener.
 *
 * <p>Every send is awaited, so when a method here returns the records are on the broker and a test
 * can go straight to asserting on what the consumer did.
 */
public final class KafkaDeliveries {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

    private KafkaDeliveries() {
    }

    /**
     * Publishes records in order and waits for each to be acknowledged.
     *
     * @param kafka   the application's own template, so its serializers apply
     * @param records the records to publish
     * @param <K>     the key type
     * @param <V>     the value type
     */
    public static <K, V> void publish(KafkaOperations<K, V> kafka, Collection<ProducerRecord<K, V>> records) {
        records.forEach(record -> send(kafka, copyOf(record)));
    }

    /**
     * Delivers one record twice.
     *
     * @param kafka  the application's own template
     * @param record the record to duplicate
     * @param <K>    the key type
     * @param <V>    the value type
     */
    public static <K, V> void duplicate(KafkaOperations<K, V> kafka, ProducerRecord<K, V> record) {
        deliver(kafka, record, 2);
    }

    /**
     * Delivers one record a number of times, with identical key, headers and payload.
     *
     * @param kafka  the application's own template
     * @param record the record to deliver
     * @param times  how many copies to publish, at least one
     * @param <K>    the key type
     * @param <V>    the value type
     */
    public static <K, V> void deliver(KafkaOperations<K, V> kafka, ProducerRecord<K, V> record, int times) {
        if (times < 1) {
            throw new IllegalArgumentException("times must be at least 1, was " + times);
        }
        for (int copy = 0; copy < times; copy++) {
            send(kafka, copyOf(record));
        }
    }

    /**
     * A fresh record carrying the same data.
     *
     * <p>The producer marks a record's headers read-only once it has been sent, and anything on the
     * send path that adds a header - tracing does - then fails on the second send of the same object.
     */
    private static <K, V> ProducerRecord<K, V> copyOf(ProducerRecord<K, V> record) {
        return new ProducerRecord<>(record.topic(), record.partition(), record.timestamp(),
                record.key(), record.value(), new RecordHeaders(record.headers().toArray()));
    }

    private static <K, V> void send(KafkaOperations<K, V> kafka, ProducerRecord<K, V> record) {
        try {
            kafka.send(record).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing to " + record.topic(), ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("Could not publish to " + record.topic()
                    + ". Is Kafka named on the local application?", ex);
        }
    }
}
