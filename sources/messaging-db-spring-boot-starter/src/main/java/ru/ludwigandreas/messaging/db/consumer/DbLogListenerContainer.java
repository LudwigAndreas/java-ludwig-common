package ru.ludwigandreas.messaging.db.consumer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.Deserializer;
import org.springframework.core.log.LogAccessor;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.GenericMessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.messaging.db.log.LogStore;
import ru.ludwigandreas.messaging.db.log.Outcome;
import ru.ludwigandreas.messaging.db.log.StoredRecord;
import ru.ludwigandreas.messaging.dlt.AuditingDeadLetterRecoverer;
import ru.ludwigandreas.messaging.settings.ResolvedConsumerSettings;

/**
 * A listener container that reads its topics from the log instead of a broker.
 *
 * <p>It runs the listener spring-kafka already adapted for the endpoint - argument resolution, payload
 * conversion and the dedup filter included - behind the same record interceptors, deserializers and
 * transaction manager the platform's builder gave the Kafka container. The retry policy is the consumer's
 * resolved settings, applied by holding the partition rather than by sleeping a thread, which is the log's
 * version of "the backoff lives in the container".
 */
@Slf4j
public class DbLogListenerContainer implements MessageListenerContainer {

    private static final LogAccessor LOG_ACCESSOR = new LogAccessor(DbLogListenerContainer.class);

    private final Spec spec;
    private final RecordInterceptor<Object, Object> interceptor;
    private final TransactionTemplate transaction;
    private volatile boolean autoStartup;
    private volatile boolean running;
    private CountDownLatch stopSignal = new CountDownLatch(0);
    private final List<Thread> workers = new ArrayList<>();

    /**
     * Creates the container.
     *
     * @param spec everything it needs, taken from the Kafka container the platform's factory built for the
     *             same endpoint
     */
    public DbLogListenerContainer(Spec spec) {
        this.spec = spec;
        this.interceptor = spec.interceptor();
        this.transaction = spec.properties().getTransactionManager() == null ? null
                : new TransactionTemplate(spec.properties().getTransactionManager());
        this.autoStartup = spec.autoStartup();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        spec.store().ensurePositions(spec.groupId(), spec.topics(), spec.earliest());
        running = true;
        stopSignal = new CountDownLatch(1);
        for (int i = 0; i < spec.concurrency(); i++) {
            Thread worker = new Thread(this::poll, "db-log-" + spec.groupId() + "-" + i);
            workers.add(worker);
            worker.start();
        }
        log.info("Reading {} from the log as group '{}' with {} worker(s)", spec.topics(), spec.groupId(),
                spec.concurrency());
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        stopSignal.countDown();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(spec.properties().getShutdownTimeout());
        for (Thread worker : workers) {
            try {
                worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        workers.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return autoStartup;
    }

    @Override
    public void setAutoStartup(boolean autoStartup) {
        this.autoStartup = autoStartup;
    }

    @Override
    public void setupMessageListener(Object messageListener) {
        // The listener is the one spring-kafka adapted for the endpoint, supplied at construction.
    }

    @Override
    public Map<String, Map<MetricName, ? extends Metric>> metrics() {
        return Map.of();
    }

    @Override
    public ContainerProperties getContainerProperties() {
        return spec.properties();
    }

    @Override
    public String getGroupId() {
        return spec.groupId();
    }

    @Override
    public String getListenerId() {
        return spec.listenerId();
    }

    private void poll() {
        while (running) {
            boolean worked = false;
            try {
                worked = spec.store().processNext(spec.groupId(), spec.topics(), this::handle);
            // CHECKSTYLE.OFF: IllegalCatch - a worker that dies on one failure stops the group's consumption
            // without a trace; the position did not move, so logging and polling again is the safe answer.
            } catch (RuntimeException e) {
            // CHECKSTYLE.ON: IllegalCatch
                log.error("Polling the log as group '{}' failed; retrying after the poll interval",
                        spec.groupId(), e);
            }
            if (!worked) {
                try {
                    stopSignal.await(spec.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    Outcome handle(LogStore.Position position, StoredRecord stored) {
        ConsumerRecord<Object, Object> record = toRecord(stored);
        Exception failure = deserializationFailure(record);
        if (failure == null) {
            failure = deliver(record);
        }
        if (failure == null) {
            return new Outcome.Advance();
        }
        ResolvedConsumerSettings settings = spec.settings();
        int failures = position.attempts() + 1;
        if (!isNonRetryable(failure) && failures < settings.maxAttempts()) {
            spec.support().metrics().recordRetry(stored.topic());
            return new Outcome.Retry(failures, backoff(settings, failures), failure.getClass().getName());
        }
        if (!settings.deadLetterEnabled()) {
            // What DefaultErrorHandler does with no recoverer: give up on the record, say so, and move on.
            log.error("Skipping the record at {}-{}@{} for group '{}' after {} attempt(s); dead-lettering is off",
                    stored.topic(), stored.partition(), stored.offset(), spec.groupId(), failures);
            return new Outcome.Advance();
        }
        try {
            new AuditingDeadLetterRecoverer(
                    (consumed, exception) -> spec.support().deadLetters()
                            .write(stored, exception, settings.deadLetterSuffix()),
                    spec.support().envelopeReader(), spec.support().metrics(), spec.support().auditSink(),
                    spec.support().clock(), settings.deadLetterSuffix(), settings.maxAttempts())
                    .accept(record, failure);
        // CHECKSTYLE.OFF: IllegalCatch - the recoverer has already counted and audited the failed send; the
        // record stays where it is and is tried again after the longest backoff.
        } catch (RuntimeException e) {
        // CHECKSTYLE.ON: IllegalCatch
            return new Outcome.Retry(failures, settings.maxBackoff(), e.getClass().getName());
        }
        return new Outcome.Advance();
    }

    private ConsumerRecord<Object, Object> toRecord(StoredRecord stored) {
        Headers headers = new RecordHeaders(stored.headers().toArray());
        Object key = stored.key() == null ? null
                : spec.keyDeserializer().deserialize(stored.topic(), headers, stored.key());
        Object value = spec.valueDeserializer().deserialize(stored.topic(), headers, stored.payload());
        return new ConsumerRecord<>(stored.topic(), stored.partition(), stored.offset(),
                stored.producedAt().toEpochMilli(), TimestampType.LOG_APPEND_TIME,
                stored.key() == null ? -1 : stored.key().length,
                stored.payload() == null ? -1 : stored.payload().length,
                key, value, headers, Optional.empty());
    }

    /** What spring-kafka's container checks before the listener: a deserializer that failed left a header. */
    private static Exception deserializationFailure(ConsumerRecord<Object, Object> record) {
        DeserializationException value = SerializationUtils.getExceptionFromHeader(record,
                SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER, LOG_ACCESSOR);
        return value != null ? value : SerializationUtils.getExceptionFromHeader(record,
                SerializationUtils.KEY_DESERIALIZER_EXCEPTION_HEADER, LOG_ACCESSOR);
    }

    @SuppressWarnings("unchecked")
    private Exception deliver(ConsumerRecord<Object, Object> record) {
        ConsumerRecord<Object, Object> intercepted = interceptor == null ? record : interceptor.intercept(record, null);
        if (intercepted == null) {
            return null;
        }
        GenericMessageListener<ConsumerRecord<Object, Object>> listener =
                (GenericMessageListener<ConsumerRecord<Object, Object>>) spec.listener();
        try {
            if (transaction != null) {
                transaction.executeWithoutResult(status -> listener.onMessage(intercepted, null, null));
            } else {
                listener.onMessage(intercepted, null, null);
            }
            if (interceptor != null) {
                interceptor.success(intercepted, null);
            }
            return null;
        // CHECKSTYLE.OFF: IllegalCatch - a listener failure is an outcome to classify, exactly as spring-kafka's
        // container hands it to the error handler.
        } catch (RuntimeException e) {
        // CHECKSTYLE.ON: IllegalCatch
            if (interceptor != null) {
                interceptor.failure(intercepted, e, null);
            }
            return e;
        } finally {
            if (interceptor != null) {
                interceptor.afterRecord(intercepted, null);
            }
        }
    }

    /** The same classification messaging's error handler registers as not retryable. */
    private static boolean isNonRetryable(Throwable exception) {
        for (Throwable cause = exception; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            if (cause instanceof DeserializationException || cause instanceof MessageConversionException) {
                return true;
            }
        }
        return false;
    }

    /** The interval before delivery {@code failures + 1}: initial, then multiplied, capped at the maximum. */
    static Duration backoff(ResolvedConsumerSettings settings, int failures) {
        double millis = settings.initialBackoff().toMillis() * Math.pow(settings.multiplier(), failures - 1);
        return Duration.ofMillis((long) Math.min(millis, settings.maxBackoff().toMillis()));
    }

    /**
     * What a log container is built from.
     *
     * @param store             the log
     * @param support           the platform collaborators every container shares
     * @param settings          the consumer's resolved settings
     * @param groupId           the consumer group
     * @param listenerId        the endpoint's listener id
     * @param topics            the topics read from the log
     * @param earliest          whether a new group starts at the beginning
     * @param concurrency       how many partitions this instance processes at once
     * @param listener          the adapted listener
     * @param keyDeserializer   the key deserializer
     * @param valueDeserializer the value deserializer, version gate included
     * @param interceptor       the composed record interceptor, or {@code null}
     * @param properties        the Kafka container's properties, for the transaction manager and shutdown timeout
     * @param autoStartup       whether the registry starts it
     * @param pollInterval      how long an idle worker waits before polling again
     */
    public record Spec(LogStore store, ConsumerSupport support, ResolvedConsumerSettings settings,
                       String groupId, String listenerId, List<String> topics, boolean earliest, int concurrency,
                       Object listener, Deserializer<Object> keyDeserializer, Deserializer<Object> valueDeserializer,
                       RecordInterceptor<Object, Object> interceptor, ContainerProperties properties,
                       boolean autoStartup, Duration pollInterval) {
    }
}
