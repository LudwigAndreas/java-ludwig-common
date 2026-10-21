package ru.ludwigandreas.messaging.db.integration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.listener.adapter.RecordFilterStrategy;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.messaging.api.MessageHeaders;
import ru.ludwigandreas.messaging.consumer.ListenerContainerFactoryBuilder;

/**
 * A service whose every consumed topic is a log topic, with a broker address nothing listens on.
 *
 * <p>The factory {@code @Bean} methods and the listeners are written exactly as a service writes them
 * against Kafka. Nothing here knows the log exists; that is what the tests prove.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableKafka
public class LogOnlyTestApplication {

    public static final String ORDERS = "it.orders";
    public static final String TEXT = "it.text";
    public static final String ORDERED = "it.ordered";
    public static final String DEDUPED = "it.deduped";
    public static final String BROKEN = "it.broken";
    public static final String CORRELATED = "it.correlated";

    public record Order(String id, String action) {
    }

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    Recorder recorder() {
        return new Recorder();
    }

    @Bean
    Listeners listeners(Recorder recorder) {
        return new Listeners(recorder);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Order> typedFactory(ListenerContainerFactoryBuilder builder) {
        return builder.forJsonPayload("typed", Order.class, ORDERS);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> textFactory(ListenerContainerFactoryBuilder builder) {
        return builder.forTextPayload("text", TEXT, CORRELATED);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> orderedFactory(ListenerContainerFactoryBuilder builder) {
        return builder.forTextPayload("ordered", ORDERED);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> dedupedFactory(ListenerContainerFactoryBuilder builder) {
        return builder.forTextPayload("deduped", DEDUPED);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> brokenFactory(ListenerContainerFactoryBuilder builder) {
        return builder.forTextPayload("broken", BROKEN);
    }

    /** The shape of the idempotency starter's filter: a repeated idempotency key is filtered out. */
    @Bean
    RecordFilterStrategy<Object, Object> dedupFilter(Recorder recorder) {
        return record -> {
            Header header = record.headers().lastHeader(MessageHeaders.IDEMPOTENCY_KEY);
            return header != null && !recorder.claimed.add(new String(header.value(), StandardCharsets.UTF_8));
        };
    }

    /** The shape of observability's correlation interceptor: the header is put in context around the listener. */
    @Bean
    RecordInterceptor<Object, Object> correlationInterceptor() {
        return new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                            Consumer<Object, Object> consumer) {
                Header header = record.headers().lastHeader(MessageHeaders.CORRELATION_ID);
                Recorder.CORRELATION.set(header == null ? null : new String(header.value(), StandardCharsets.UTF_8));
                return record;
            }

            @Override
            public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
                Recorder.CORRELATION.remove();
            }
        };
    }

    @Bean
    AuditSink auditSink(Recorder recorder) {
        return recorder.audits::add;
    }

    public static class Recorder {

        static final ThreadLocal<String> CORRELATION = new ThreadLocal<>();

        public final Map<String, List<String>> delivered = new ConcurrentHashMap<>();
        public final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        public final List<AuditEvent> audits = Collections.synchronizedList(new ArrayList<>());
        public final Set<String> failing = ConcurrentHashMap.newKeySet();
        final Set<String> claimed = ConcurrentHashMap.newKeySet();

        void record(String channel, String value) {
            delivered.computeIfAbsent(channel, key -> Collections.synchronizedList(new ArrayList<>())).add(value);
        }

        int attempt(String value) {
            return attempts.computeIfAbsent(value, key -> new AtomicInteger()).incrementAndGet();
        }

        public List<String> on(String channel) {
            return delivered.getOrDefault(channel, List.of());
        }
    }

    public static class Listeners {

        private final Recorder recorder;

        Listeners(Recorder recorder) {
            this.recorder = recorder;
        }

        @KafkaListener(topics = ORDERS, groupId = "it-billing", containerFactory = "typedFactory")
        public void billing(ConsumerRecord<String, Order> record) {
            recorder.record("billing", record.value().id());
        }

        @KafkaListener(topics = ORDERS, groupId = "it-search", containerFactory = "typedFactory")
        public void search(ConsumerRecord<String, Order> record) {
            recorder.record("search", record.value().id());
        }

        @KafkaListener(topics = TEXT, groupId = "it-text", containerFactory = "textFactory")
        public void text(ConsumerRecord<String, String> record) {
            recorder.attempt(record.value());
            recorder.record(TEXT, record.value());
            if (recorder.failing.contains(record.value())) {
                throw new IllegalStateException("the projection could not apply this");
            }
        }

        @KafkaListener(topics = CORRELATED, groupId = "it-correlated", containerFactory = "textFactory")
        public void correlated(String value) {
            recorder.record(CORRELATED, value + "@" + Recorder.CORRELATION.get());
        }

        /** Fails the first delivery of a value ending in {@code !}, so a retried record must keep its place. */
        @KafkaListener(topics = ORDERED, groupId = "it-ordered", containerFactory = "orderedFactory",
                concurrency = "3")
        public void ordered(ConsumerRecord<String, String> record) {
            if (record.value().endsWith("!") && recorder.attempt(record.value()) == 1) {
                throw new IllegalStateException("fails once, on purpose");
            }
            recorder.record(ORDERED + ":" + record.key(), record.value());
        }

        @KafkaListener(topics = DEDUPED, groupId = "it-deduped", containerFactory = "dedupedFactory")
        public void deduped(String value) {
            recorder.record(DEDUPED, value);
        }

        @KafkaListener(topics = BROKEN, groupId = "it-broken", containerFactory = "brokenFactory")
        public void broken(String value) {
            recorder.attempt(BROKEN + value);
            throw new IllegalStateException("always fails");
        }
    }
}
